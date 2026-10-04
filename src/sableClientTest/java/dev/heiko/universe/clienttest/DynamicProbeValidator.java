package dev.heiko.universe.clienttest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Offline evidence only. Never changes a world, copies a server pose into a client, or issues final PASS. */
public final class DynamicProbeValidator {
    private static final long JSON_LIMIT=1_048_576, PNG_LIMIT=8_388_608, SOURCE_LIMIT=2_097_152;
    private static final String PENDING="DYNAMIC_CAPTURED_PENDING_VALIDATION";
    private static final String SELF="src/sableClientTest/java/dev/heiko/universe/clienttest/DynamicProbeValidator.java";
    private final Path root, run, serverDir, clientDir, frameDir, output;
    private final String runId;
    private String nonce, serverNonce, clientNonce, uuid;
    private Boolean tcpRequested;
    private long created, serverFinished, clientFinished;
    private JsonObject serverReport, clientReport;
    private final Map<String,Object> checks=new LinkedHashMap<>(), measurements=new LinkedHashMap<>();
    private final Map<String,String> artifactHashes=new LinkedHashMap<>();
    private final Map<String,JsonObject> captures=new LinkedHashMap<>();
    private final Set<Long> frameIds=new HashSet<>();
    private final List<JsonObject> held=new ArrayList<>(), live=new ArrayList<>(), stages=new ArrayList<>(), telemetry=new ArrayList<>();

    private DynamicProbeValidator(Path workspace,String id)throws Exception {
        require(id.matches("[A-Za-z0-9_-]{1,64}"),"Invalid run ID");
        root=workspace.toAbsolutePath().normalize().toRealPath();runId=id;
        run=inside(root.resolve(".tooling/client-probes/"+id));
        // Missing role/capture directories are evidence failures recorded by main, not constructor failures.
        serverDir=run.resolve("server");clientDir=run.resolve("client");
        frameDir=clientDir.resolve("dynamic-frames");output=run.resolve("numeric-pixel-validation.json");
        require(!Files.exists(output),"Immutable validation already exists");
    }

    public static void main(String[] arguments)throws Exception {
        require(arguments.length==2,"Usage: DynamicProbeValidator <absolute workspace> <runId>");
        var validator=new DynamicProbeValidator(Path.of(arguments[0]),arguments[1]);
        try {
            validator.validate();
            validator.write("NUMERIC_PIXEL_PASSED_PENDING_MANUAL_REVIEW",null);
        } catch(Throwable failure) {
            try {validator.write("FAILED",failure);} catch(Throwable writing) {if(writing!=failure)failure.addSuppressed(writing);}
            if(failure instanceof Exception exception)throw exception;
            if(failure instanceof Error error)throw error;
            throw new IllegalStateException(failure);
        }
    }

    private void validate()throws Exception {
        provenance();fixture();heldFrames();telemetry();liveFrames();completion();cleanup();pixels();
    }

    private void provenance()throws Exception {
        var session=read(run.resolve("session.json"));
        equal(text(session,"runId"),runId,"Session run");require(bool(session,"dynamic"),"Session must be dynamic");
        nonce=text(session,"sessionNonce");require(nonce.matches("[A-Za-z0-9_-]{16,80}"),"Invalid session nonce");
        serverNonce=canonicalUuid(text(session,"serverRuntimeNonce"));created=integer(session,"createdAtMillis");
        var launch=read(root.resolve(".tooling/client-launches/"+runId+".json"));
        serverReport=read(serverDir.resolve("report.json"));clientReport=read(clientDir.resolve("report.json"));
        uuid=canonicalUuid(text(serverReport,"uuid"));clientNonce=canonicalUuid(text(clientReport,"runtimeNonce"));
        require(!clientNonce.equals(serverNonce),"Distinct client/server runtimes required");
        serverFinished=integer(serverReport,"writtenAtMillis");clientFinished=integer(clientReport,"writtenAtMillis");
        require(serverFinished>=created&&clientFinished>=created,"Reports predate session");
        JsonObject previousSources=null;String coreHash=null;Set<Long> pids=new HashSet<>();
        for(String role:List.of("server","client")) {
            JsonObject report=role.equals("server")?serverReport:clientReport;
            JsonObject roleLaunch=object(launch,role);
            Path work=inside(root.resolve(".tooling/client-runs/"+runId+"/"+role));
            var supervisor=read(work.resolve("supervisor.json"));
            envelope(report,role.equals("server")?serverNonce:clientNonce,true);
            equal(text(report,"status"),PENDING,"Report status");equal(text(report,"visualAcceptance"),"NOT_EVALUATED","Visual status");
            require(text(report,"javaVersion").startsWith("21."),"Pinned Java 21 runtime required");
            equal(text(supervisor,"role"),role,"Supervisor role");equal(text(supervisor,"runId"),runId,"Supervisor run");
            equal(text(supervisor,"nonce"),nonce,"Supervisor nonce");require(bool(supervisor,"dynamic"),"Supervisor mode");
            boolean roleTcp=bool(supervisor,"tcpRequested");if(tcpRequested!=null)require(tcpRequested==roleTcp,"Role transport settings differ");tcpRequested=roleTcp;
            equal(text(supervisor,"status"),"EXITED","Actual orderly supervisor exit");
            require(integer(supervisor,"exitCode")==0,"Actual JVM exit must be zero");
            require(!supervisor.has("supervisorError")||supervisor.get("supervisorError").isJsonNull()
                    ||text(supervisor,"supervisorError").isEmpty(),"Supervisor failed");
            long pid=integer(supervisor,"pid");require(pid>0&&pids.add(pid)&&integer(report,"pid")==pid,"Actual distinct owned PID/report mismatch");
            equal(Path.of(text(supervisor,"workingDirectory")).toAbsolutePath().normalize(),work,"Owned role directory");
            equal(Path.of(text(roleLaunch,"workingDirectory")).toAbsolutePath().normalize(),work,"Launch role directory");
            equal(text(supervisor,"executable"),text(roleLaunch,"executable"),"Executable provenance");
            equal(text(roleLaunch,"runId"),runId,"Launch run");equal(text(roleLaunch,"nonce"),nonce,"Launch nonce");
            require(bool(roleLaunch,"dynamic"),"Launch dynamic mode required");
            long processStarted=Instant.parse(text(supervisor,"startedAtUtc")).toEpochMilli();
            long runtimeStarted=integer(report,"runtimeStartedAtMillis"), finished=integer(report,"writtenAtMillis");
            require(processStarted<=runtimeStarted&&runtimeStarted<=finished,"Process/runtime/report clock ordering");
            if(role.equals("server"))require(runtimeStarted<=created,"Server runtime must create this session");
            else require(runtimeStarted>=created&&runtimeStarted-created<=120_000,"Fresh client runtime required");
            var sources=object(supervisor,"sourceSha256");require(sources.size()>0&&sources.size()<=256,"Source manifest cap");
            Set<String> keys=new HashSet<>();
            for(var entry:sources.entrySet()) {
                String relative=entry.getKey().replace('\\','/');require(keys.add(relative),"Duplicate normalized source path");
                Path path=inside(root.resolve(relative));String hash=hash(path,SOURCE_LIMIT);
                hashEqual(hash,entry.getValue().getAsString(),"Source changed since launch: "+relative);
            }
            require(keys.contains(SELF)&&keys.contains("build.gradle")&&keys.contains("tools/start-client-probe.ps1")
                    &&keys.contains("tools/run-dynamic-client-probe.ps1"),"Validator/build/supervisors must be frozen BEFORE launch");
            if(previousSources!=null)require(previousSources.equals(sources),"Role source manifests differ");previousSources=sources;
            var arguments=array(roleLaunch,"arguments");require(arguments.size()<=128,"Launch argument cap");
            Set<Path> expectedArguments=new HashSet<>();
            for(var argument:arguments){String value=argument.getAsString();if(value.startsWith("@"))expectedArguments.add(inside(Path.of(value.substring(1))));}
            var files=array(supervisor,"argumentFiles");require(files.size()>=2&&files.size()<=8,"Frozen argument file cap");
            Set<Path> actualArguments=new HashSet<>();Path bundle=inside(root.resolve(".tooling/client-launches/"+runId));
            for(var element:files){var file=element.getAsJsonObject();Path path=inside(Path.of(text(file,"path")));
                require(path.startsWith(bundle)&&actualArguments.add(path),"Argument file outside frozen run/duplicate");
                hashEqual(hash(path,JSON_LIMIT),text(file,"sha256"),"Frozen arguments changed");}
            equal(actualArguments,expectedArguments,"Argument provenance differs from actual launch");
            String roleCore=text(supervisor,"coreJarSha256");if(coreHash!=null)hashEqual(coreHash,roleCore,"Role core hashes differ");coreHash=roleCore;
            measurements.put(role+"Ownership",Map.of("pid",pid,"startedAtUtc",text(supervisor,"startedAtUtc"),
                    "javaVersion",text(report,"javaVersion"),"runtimeNonce",text(report,"runtimeNonce"),"exitCode",0));
        }
        Path library=inside(root.resolve("build/libs"));List<Path> jars;
        try(var stream=Files.list(library)){jars=stream.filter(path->path.getFileName().toString().endsWith(".jar")).limit(17).toList();}
        require(jars.size()<=16,"Core jar listing cap");Path matched=null;
        for(Path jar:jars)if(hash(jar,67_108_864).equalsIgnoreCase(coreHash)){require(matched==null,"Ambiguous core artifact hash");matched=jar;}
        require(matched!=null,"Frozen core jar hash missing/changed");
        measurements.put("coreJar",Map.of("path",root.relativize(matched).toString(),"sha256",coreHash));
        check("ownedProcessesAndFrozenProvenance");
        measurements.put("transportMode",tcpRequested?"TCP_CONFIGURED_FIXTURE":"DEFAULT_UDP_CONFIGURED_FIXTURE");
    }

    private void fixture()throws Exception {
        var manifest=read(serverDir.resolve("dynamic-fixture-manifest.json"));envelope(manifest,serverNonce,false);
        equal(text(manifest,"scenario"),"dynamic","Fixture scenario");require(bool(manifest,"ordinaryFixtureVerified"),"Actual ordinary geometry was not verified");
        require(bool(manifest,"actualCommonAttemptUdp")!=tcpRequested,"Actual server transport setting mismatch");
        equalNumbers(numbers(manifest,"wallMin",3),new double[]{2,84,-4},0,"Wall min");
        equalNumbers(numbers(manifest,"wallMax",3),new double[]{2,90,-4},0,"Wall max");
        cameraConfiguration(manifest);landmark(object(manifest,"landmark"));policy(object(manifest,"fixturePolicy"));
        require(number(manifest,"bodyBaselineAroundY")==88,"Unexpected initial fixture height");
        for(int stage=0;stage<4;stage++) {
            var state=read(serverDir.resolve("dynamic-stage-"+stage+".json"));envelope(state,serverNonce,true);
            equal(text(state,"phase"),"HELD","Held descriptor phase");require(integer(state,"stage")==stage,"Descriptor stage mismatch");
            require(Math.abs(number(state,"simulationSeconds")-.35*stage)<=1e-8,"Held simulation boundary mismatch");
            require(bool(state,"ordinaryFixtureVerified"),"Ordinary fixture changed");policy(object(state,"fixturePolicy"));
            require(bool(state,"actualCommonAttemptUdp")!=tcpRequested,"Actual held server transport setting mismatch");
            landmark(object(state,"landmark"));cameraConfiguration(state);pose(state);
            long ready=integer(state,"readyAtMillis");require(ready>=created&&ready<=clientFinished,"Held descriptor clock");
            require(integer(state,"writtenAtMillis")>=ready,"Descriptor precedes ready boundary");
            canonicalUuid(text(state,"stateEpoch"));stages.add(state);
        }
        require(stages.stream().map(stage->text(stage,"stateEpoch")).distinct().count()==4,"Distinct stage epochs required");
        equalNumbers(numbers(stages.get(0),"markerOrigin",3),numbers(stages.get(3),"markerOrigin",3),0,"Native storage origin changed");
        measurements.put("fixtureManifest",manifest);check("actualFixtureAndFourHeldEpochs");
    }

    private void heldFrames()throws Exception {
        long previous=-1;
        for(int stage=0;stage<4;stage++) {
            var ack=read(clientDir.resolve("dynamic-ack-"+stage+".json"));envelope(ack,clientNonce,true);
            require(integer(ack,"stage")==stage,"ACK stage");equal(text(ack,"stateEpoch"),text(stages.get(stage),"stateEpoch"),"ACK epoch");
            equal(text(ack,"serverRuntimeNonce"),serverNonce,"ACK server runtime");
            var summaries=array(ack,"frames");require(summaries.size()==3,"Exactly three held frames per stage");
            for(var summary:summaries) {
                var frame=loadFrame(summary.getAsJsonObject(),stage);
                long id=integer(frame,"frameId");require(id>previous,"Global held frame order");previous=id;
                equal(text(frame,"stateEpoch"),text(stages.get(stage),"stateEpoch"),"Held capture epoch");
                equal(text(frame,"observedPhase"),"HELD","Held capture phase");
                require(integer(frame,"capturedAtMillis")>=integer(stages.get(stage),"readyAtMillis"),"Capture predates held boundary");
                double[] error=poseError(frame,stages.get(stage));require(error[0]<=.10&&error[1]<=.03,"Held client/server pose mismatch");
                equalNumbers(numbers(frame,"markerOrigin",3),numbers(stages.get(stage),"markerOrigin",3),0,"Actual marker origin");
                held.add(frame);append("heldPoseErrors",Map.of("file",text(frame,"file"),"stage",stage,"positionError",error[0],"angleError",error[1]));
            }
            require(integer(ack,"writtenAtMillis")>=integer(held.get(held.size()-1),"writtenAtMillis"),"ACK predates saved PNG metadata");
        }
        require(held.size()==12,"Twelve actual held images required");check("heldPngsAndActualClientServerAgreement");
    }

    private void telemetry()throws Exception {
        var document=read(serverDir.resolve("dynamic-telemetry.json"));envelope(document,serverNonce,true);
        var rows=array(document,"samples");require(rows.size()>=46&&rows.size()<=512,"Telemetry cap/minimum");
        double seconds=0;double[] integral={0,0,0}, previousV=null;int steps=0, zeroRows=0;long previousClock=created,previousGameTime=Long.MIN_VALUE;
        for(var element:rows) {
            var row=element.getAsJsonObject();equal(text(row,"uuid"),uuid,"Telemetry UUID");pose(row);
            long clock=integer(row,"capturedAtMillis");require(clock>=previousClock&&clock<=serverFinished,"Telemetry wall-clock order");previousClock=clock;
            long gameTime=integer(row,"gameTime");require(gameTime>=previousGameTime,"Telemetry game-time order");previousGameTime=gameTime;
            double dt=number(row,"dt");double[] velocity=numbers(row,"velocity",3);
            equal(text(row,"integralFormula"),"sum((previousNativeV+postNativeV)*0.5*dt)","Frozen native integral formula");
            if(dt==0) {
                zeroRows++;
                require(integer(row,"stage")==Math.max(0,zeroRows-2),"Setup/resume telemetry stage");
                if(zeroRows==1){equalNumbers(velocity,new double[]{0,0,0},1e-6,"Baseline must be stopped");equalNumbers(numbers(row,"omega",3),new double[]{0,0,0},1e-6,"Baseline omega");}
                else if(zeroRows==2){equalNumbers(velocity,new double[]{2,3,0},1e-6,"Immediate target V");equalNumbers(numbers(row,"omega",3),new double[]{0,.5,0},1e-6,"Immediate target omega");}
                else {require(previousV!=null,"Resume missing previous endpoint");equalNumbers(velocity,previousV,1e-6,"Resume changed held velocity");}
            } else {
                require(Math.abs(dt-.025)<=1e-10&&previousV!=null,"Calibrated native timestep/initial endpoint");
                require(integer(row,"stage")==steps/14,"Native interval telemetry stage");
                double[] before=numbers(row,"previousNativeV",3), after=numbers(row,"postNativeV",3);
                equalNumbers(before,previousV,1e-8,"Trapezoid previous endpoint");equalNumbers(after,velocity,1e-8,"Trapezoid current endpoint");
                for(int axis=0;axis<3;axis++){integral[axis]+=.5*dt*(before[axis]+after[axis]);require(Double.isFinite(integral[axis]),"Nonfinite recomputed integral");}
                seconds+=dt;steps++;
            }
            require(Math.abs(number(row,"simulationSeconds")-seconds)<=1e-8,"Telemetry time sum");
            equalNumbers(numbers(row,"velocityIntegral",3),integral,1e-8,"Reported integral differs from independent trapezoid");
            previousV=velocity;telemetry.add(row);
        }
        require(steps==42&&zeroRows==4&&Math.abs(seconds-1.05)<=1e-8,"Exactly 42 native steps and four setup/resume boundaries required");
        var first=stages.get(0);var last=stages.get(3);double[] delta=subtract(numbers(last,"position",3),numbers(first,"position",3));
        double discrepancy=distance(delta,integral), angle=angle(numbers(first,"orientation",4),numbers(last,"orientation",4));
        require(discrepancy<=.04&&delta[0]>=1.4&&angle>=.25&&numbers(last,"position",3)[1]>=83,"Native displacement/rotation/landing/integral gate");
        equalNumbers(numbers(telemetry.get(telemetry.size()-1),"position",3),numbers(last,"position",3),1e-8,"Final native pose descriptor");
        measurements.put("nativeMotion",Map.of("steps",steps,"zeroDtRows",zeroRows,"simulationSeconds",seconds,"integral",integral,
                "displacement",delta,"integralDiscrepancy",discrepancy,"angularDisplacement",angle,"finalY",numbers(last,"position",3)[1]));
        check("independentNativeTrapezoidAndMotion");
    }

    private void liveFrames()throws Exception {
        var ack=read(clientDir.resolve("dynamic-ack-3.json"));var summaries=array(ack,"liveEvidence");
        require(summaries.size()>=3&&summaries.size()<=24&&integer(ack,"liveFrames")==summaries.size(),"Live evidence count/cap");
        long previous=-1;
        for(var summary:summaries) {
            var record=summary.getAsJsonObject();String file=text(record,"file");
            require(file.matches("stage-(100|101|102)-frame-[0-9]+\\.png"),"Unexpected live file");
            int stage=Integer.parseInt(file.substring(6,9));var frame=loadFrame(record,stage);
            long id=integer(frame,"frameId");require(id>previous,"Live frame ordering");previous=id;
            equal(text(frame,"observedPhase"),"MOVING","Live phase");equal(text(frame,"stateEpoch"),text(stages.get(stage-100),"stateEpoch"),"Live leg epoch");
            double time=number(frame,"serverSimulationSeconds");require(time>=.35*(stage-100)-1e-8&&time<=.35*(stage-99)+1e-8,"Live observed simulation interval");
            long captured=integer(frame,"capturedAtMillis"), bestAge=Long.MAX_VALUE;int best=-1;double[] bestError=null;
            for(int index=0;index<telemetry.size();index++) {
                var sample=telemetry.get(index);long age=Math.abs(captured-integer(sample,"capturedAtMillis"));
                if(age>250)continue;double[] error=poseError(frame,sample);
                if(error[0]<=.25&&error[1]<=.08&&age<bestAge){best=index;bestAge=age;bestError=error;}
            }
            require(best>=0,"No jointly matching actual server sample within 250ms: "+file);
            append("liveMatches",Map.of("file",file,"frameId",id,"capturedAtMillis",captured,"sampleIndex",best,"ageMillis",bestAge,
                    "positionError",bestError[0],"angleError",bestError[1],"serverSimulationSeconds",number(telemetry.get(best),"simulationSeconds")));
            live.add(frame);
        }
        var first=live.get(0);var last=live.get(live.size()-1);
        double displacement=distance(numbers(first,"position",3),numbers(last,"position",3));
        double rotation=angle(numbers(first,"orientation",4),numbers(last,"orientation",4));
        require(displacement>=.5&&rotation>=.1,"Live client pose did not actually change by .5m and .1rad");
        measurements.put("liveClientMotion",Map.of("count",live.size(),"displacement",displacement,"angularDisplacement",rotation));
        var raw=array(clientReport,"frames");require(raw.size()==12+live.size()&&raw.size()<=36,"Client report capture count");
        Set<String> reported=new HashSet<>();long lastId=-1,lastCapture=created,lastWritten=created;
        for(var element:raw){var frame=element.getAsJsonObject();String file=text(frame,"file");require(reported.add(file),"Duplicate report frame");
            require(captures.containsKey(file)&&captures.get(file).equals(frame),"Raw client report differs from saved actual metadata");
            long id=integer(frame,"frameId");require(id>lastId,"Global chronological report frame order");lastId=id;
            long captured=integer(frame,"capturedAtMillis"),written=integer(frame,"writtenAtMillis");
            require(captured>=lastCapture&&written>=lastWritten,"Global capture/metadata clock ordering");lastCapture=captured;lastWritten=written;}
        equal(reported,captures.keySet(),"Report does not cover exact held/live union");
        require(integer(clientReport,"liveFrames")==live.size()&&integer(clientReport,"acknowledgedStage")==3,"Final client count/stage");
        check("changingLiveClientPosesAndTimestampedNativeAgreement");
    }

    private JsonObject loadFrame(JsonObject summary,int stage)throws Exception {
        String file=text(summary,"file");require(file.matches("stage-"+stage+"-frame-[0-9]+\\.png"),"Unsafe/unexpected frame name");
        require(!captures.containsKey(file),"Duplicate global capture filename");Path png=inside(frameDir.resolve(file));
        var frame=read(frameDir.resolve(file+".json"));envelope(frame,clientNonce,true);
        equal(text(frame,"serverRuntimeNonce"),serverNonce,"Frame server runtime");equal(text(frame,"file"),file,"Frame filename");
        require(integer(frame,"stage")==stage,"Frame stage");long id=integer(frame,"frameId");
        require(id>=0&&id==integer(summary,"frameId")&&frameIds.add(id),"Duplicate/global mismatched frame ID");
        equal(file,"stage-"+stage+"-frame-"+id+".png","Encoded frame ID differs from metadata");
        String hash=hash(png,PNG_LIMIT);hashEqual(hash,text(summary,"sha256"),"Actual PNG SHA mismatch");
        // A held real scene may produce identical bytes in separate fresh frames: validate each own hash.
        BufferedImage decoded=image(png);decoded.flush();
        long captured=integer(frame,"capturedAtMillis"),written=integer(frame,"writtenAtMillis");
        require(captured>=created&&written>=captured&&written<=clientFinished,"Capture/session/report clock ordering");
        long modified=Files.getLastModifiedTime(png).toMillis();require(modified>=created&&modified<=clientFinished,"Actual PNG file freshness");
        require(integer(frame,"width")==960&&integer(frame,"height")==540,"Pinned frame dimensions");
        equal(text(frame,"visualAcceptance"),"NOT_EVALUATED","Raw frame visual status");pose(frame);
        require(bool(frame,"actualCommonAttemptUdp")!=tcpRequested&&bool(frame,"actualClientAttemptUdp")!=tcpRequested,"Actual client transport settings mismatch");
        equalNumbers(numbers(frame,"scale",3),new double[]{1,1,1},0,"Unit actual render scale required");
        numbers(frame,"rotationPoint",3);numbers(frame,"camera",3);numbers(frame,"modelView",16);double[] projection=numbers(frame,"projection",16);
        quaternion(numbers(frame,"cameraQuaternion",4));
        equalNumbers(numbers(frame,"cameraPlayerPosition",3),new double[]{0,94,-12},1,"Actual camera player position");
        require(Math.abs(number(frame,"cameraPlayerYaw"))<=1&&Math.abs(number(frame,"cameraPlayerPitch")-30)<=1,"Actual camera angles");
        require(integer(frame,"actualCameraBaseFov")==70&&number(frame,"actualCameraFovEffectScale")==0,
                "Actual owned fixed camera options required");
        require(Math.abs(number(frame,"projectionVerticalFovDegrees")-70)<=.1,"Pinned actual FOV 70 required");
        require(projection[5]>0,"Positive actual vertical projection scale required");
        double derivedFov=Math.toDegrees(2*Math.atan(1.0/projection[5]));
        require(Double.isFinite(derivedFov)&&Math.abs(derivedFov-70)<=.1
                &&Math.abs(derivedFov-number(frame,"projectionVerticalFovDegrees"))<=1e-4
                &&Math.abs(projection[0]-projection[5]*540.0/960.0)<=1e-6,"Actual matrix/scalar FOV/aspect mismatch");
        equal(text(frame,"posePartialMode"),"ACTUAL_RENDERER_TIMER_TRUE","Actual renderer interpolation mode");landmark(object(frame,"landmark"));
        require(text(frame,"remoteEndpoint").matches("(?:/)?(?:127\\.0\\.0\\.1|127\\.0\\.0\\.1/127\\.0\\.0\\.1|localhost/127\\.0\\.0\\.1|\\[0:0:0:0:0:0:0:1\\]|\\[::1\\]):25575"),"Actual loopback endpoint");
        captures.put(file,frame);return frame;
    }

    private void completion()throws Exception {
        var terminal=read(clientDir.resolve("dynamic-complete-ack.json"));envelope(terminal,clientNonce,true);
        equal(text(terminal,"status"),PENDING,"Completion receipt status");equal(text(terminal,"serverRuntimeNonce"),serverNonce,"Completion server runtime");
        equal(text(terminal,"stateEpoch"),text(stages.get(3),"stateEpoch"),"Completion epoch");
        require(integer(terminal,"heldFrames")==12&&integer(terminal,"liveFrames")==live.size()&&integer(terminal,"acknowledgedStage")==3,"Completion receipt counts");
        require(integer(terminal,"writtenAtMillis")>=clientFinished,"Report must precede completion receipt");
        var state=read(serverDir.resolve("dynamic-state.json"));envelope(state,serverNonce,true);equal(text(state,"phase"),"EVIDENCE_COMPLETE","Actual terminal server state");
        require(integer(state,"stage")==3&&integer(state,"writtenAtMillis")<=clientFinished,"Terminal notification precedes client report");
        require(bool(serverReport,"numericMotionPassed"),"Server numeric gate failed");check("ordinaryCompletionReceiptAndPendingOnlyReports");
    }

    private void cleanup()throws Exception {
        var state=read(serverDir.resolve("dynamic-cleanup.json"));envelope(state,serverNonce,true);
        for(String field:List.of("ticketReleased","nativeUnregistered","listenerUnregistered","pauseRestored","callbacksStopped","fixturePolicyRestored"))
            require(bool(state,field),"Cleanup ownership unresolved: "+field);
        require(integer(state,"ordinaryBlocksRemaining")==0&&integer(state,"forcedChunksRemaining")==0&&text(state,"error").isEmpty(),"Actual cleanup failure");
        require(integer(state,"writtenAtMillis")>=integer(read(clientDir.resolve("dynamic-complete-ack.json")),"writtenAtMillis"),"Cleanup precedes final receipt");
        measurements.put("cleanup",state);check("nativeListenerPauseAndWorldOwnershipClean");
        var camera=object(clientReport,"cameraOptions");
        require(bool(camera,"claimed")&&bool(camera,"restored")&&text(camera,"error").isEmpty(),"Camera option ownership not restored");
        require(bool(camera,"matrixReady")&&integer(camera,"settlingSkippedWorldFrames")>=0
                &&integer(camera,"settlingSkippedWorldFrames")<=300,"Bounded real camera settling evidence required");
        require(integer(camera,"baseFov")==70&&number(camera,"fixtureFovEffectScale")==0,"Fixed camera policy mismatch");
        double original=number(camera,"originalFovEffectScale");
        require(original>=0&&original<=1&&number(camera,"restoredFovEffectScale")==original
                &&number(camera,"persistedFovEffectScale")==original,"Original camera option was not preserved");
        Path options=inside(root.resolve(".tooling/client-runs/"+runId+"/client/options.txt"));
        hash(options,131_072);List<String> saved=Files.readAllLines(options).stream().filter(line->line.startsWith("fovEffectScale:")).toList();
        require(saved.size()==1,"Missing/duplicate persisted camera option");
        double persisted=Double.parseDouble(saved.get(0).substring("fovEffectScale:".length()));
        require(Double.isFinite(persisted)&&persisted==original,"Actual options file differs from original camera value");
        measurements.put("cameraOptions",camera);check("actualCameraOptionAndPersistedRestore");
    }

    private void pixels()throws Exception {
        List<DynamicPixelOracle.Frame> frames=new ArrayList<>();
        for(var frame:held) {
            double[] p=numbers(frame,"position",3),q=numbers(frame,"orientation",4),r=numbers(frame,"rotationPoint",3),s=numbers(frame,"scale",3),c=numbers(frame,"camera",3);
            var snapshot=new DynamicGeometryMapper.FrameSnapshot(integer(frame,"frameId"),960,540,vec(p),
                    new DynamicGeometryMapper.Quaternion(q[0],q[1],q[2],q[3]),vec(r),vec(s),vec(c),floats(frame,"modelView"),floats(frame,"projection"));
            double[] origin=numbers(frame,"markerOrigin",3);for(double value:origin)require(value==(int)value,"Integer storage origin required");
            var fixture=DynamicGeometryMapper.standardFixture(new DynamicGeometryMapper.Cell((int)origin[0],(int)origin[1],(int)origin[2]));
            var geometry=DynamicGeometryMapper.map(snapshot,fixture);String file=text(frame,"file");
            frames.add(new DynamicPixelOracle.Frame(file,(int)integer(frame,"stage"),image(frameDir.resolve(file)),geometry));
        }
        try {measurements.put("pixels",DynamicPixelOracle.verify(frames));check("independentDepthMasksLandmarkOccupancyMotionAndBasis");}
        finally {for(var frame:frames)frame.image().flush();}
    }

    private void envelope(JsonObject row,String runtime,boolean requireUuid) {
        equal(text(row,"runId"),runId,"Artifact run");equal(text(row,"sessionNonce"),nonce,"Artifact nonce");require(bool(row,"dynamic"),"Artifact dynamic mode");
        equal(text(row,"runtimeNonce"),runtime,"Artifact runtime nonce");
        if(requireUuid)equal(text(row,"uuid"),uuid,"Artifact body UUID");
        require(integer(row,"writtenAtMillis")>=created,"Artifact predates session");
    }
    private static void cameraConfiguration(JsonObject row){equalNumbers(numbers(row,"cameraPlayerPosition",3),new double[]{0,94,-12},0,"Configured camera");require(number(row,"cameraYaw")==0&&number(row,"cameraPitch")==30,"Configured camera rotation");}
    private static void landmark(JsonObject row){equalNumbers(numbers(row,"position",3),new double[]{-4,83,-6},0,"Independent landmark coordinates");equal(text(row,"block"),"minecraft:cyan_concrete","Actual landmark block");require(bool(row,"actualBlockVerified"),"Landmark block not actually verified");}
    private static void policy(JsonObject row){equalNumbers(numbers(row,"gravity",3),new double[]{0,-11,0},1e-8,"Asserted native gravity");require(Math.abs(number(row,"universalDrag")-.09)<=1e-8&&integer(row,"substeps")==2&&integer(row,"originalSubsteps")==2,"Native drag/substeps policy");equal(text(row,"configurationMode"),"ASSERT_ONLY_GRAVITY_DRAG_SAVE_RESTORE_SUBSTEPS","Fixture policy mode");}
    private static void pose(JsonObject row){numbers(row,"position",3);quaternion(numbers(row,"orientation",4));if(row.has("velocity"))numbers(row,"velocity",3);if(row.has("omega"))numbers(row,"omega",3);}
    private static double[] poseError(JsonObject a,JsonObject b){return new double[]{distance(numbers(a,"position",3),numbers(b,"position",3)),angle(numbers(a,"orientation",4),numbers(b,"orientation",4))};}
    private static double angle(double[] a,double[] b){quaternion(a);quaternion(b);double dot=0,an=0,bn=0;for(int i=0;i<4;i++){dot+=a[i]*b[i];an+=a[i]*a[i];bn+=b[i]*b[i];}return 2*Math.acos(Math.min(1,Math.abs(dot)/Math.sqrt(an*bn)));}
    private static void quaternion(double[] q){double norm=0;for(double x:q)norm+=x*x;require(Double.isFinite(norm)&&Math.abs(norm-1)<=1e-5,"Observed quaternion must already be unit");}
    private static double distance(double[] a,double[] b){return Math.hypot(Math.hypot(a[0]-b[0],a[1]-b[1]),a[2]-b[2]);}
    private static double[] subtract(double[] a,double[] b){return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    private static void equalNumbers(double[] a,double[] b,double tolerance,String message){require(a.length==b.length,message);for(int i=0;i<a.length;i++)require(Double.isFinite(a[i])&&Double.isFinite(b[i])&&Math.abs(a[i]-b[i])<=tolerance,message+" component "+i);}
    private static DynamicGeometryMapper.Vec3 vec(double[] value){return new DynamicGeometryMapper.Vec3(value[0],value[1],value[2]);}
    private static float[] floats(JsonObject row,String field){double[] source=numbers(row,field,16);float[] values=new float[16];for(int i=0;i<16;i++){values[i]=(float)source[i];require(Float.isFinite(values[i]),"Matrix float overflow");}return values;}
    private static double[] numbers(JsonObject row,String field,int count){var array=array(row,field);require(array.size()==count,"Numeric array shape: "+field);double[] values=new double[count];for(int i=0;i<count;i++){require(array.get(i).isJsonPrimitive()&&array.get(i).getAsJsonPrimitive().isNumber(),"Non-numeric array member "+field);values[i]=array.get(i).getAsDouble();require(Double.isFinite(values[i]),"Nonfinite "+field);}return values;}
    private static JsonObject object(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonObject(),"Missing object "+field);return row.getAsJsonObject(field);}
    private static JsonArray array(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonArray(),"Missing array "+field);return row.getAsJsonArray(field);}
    private static String text(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonPrimitive()&&row.getAsJsonPrimitive(field).isString(),"Missing string "+field);return row.get(field).getAsString();}
    private static boolean bool(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonPrimitive()&&row.getAsJsonPrimitive(field).isBoolean(),"Missing boolean "+field);return row.get(field).getAsBoolean();}
    private static long integer(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonPrimitive()&&row.getAsJsonPrimitive(field).isNumber(),"Missing integer "+field);try{return row.get(field).getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalStateException("Nonintegral/overflow "+field,error);}}
    private static double number(JsonObject row,String field){require(row.has(field)&&row.get(field).isJsonPrimitive()&&row.getAsJsonPrimitive(field).isNumber(),"Missing numeric "+field);double value=row.get(field).getAsDouble();require(Double.isFinite(value),"Nonfinite "+field);return value;}
    private static String canonicalUuid(String value){require(UUID.fromString(value).toString().equals(value),"Noncanonical UUID");return value;}
    private static void hashEqual(String actual,String expected,String message){require(expected.matches("(?i)[0-9a-f]{64}")&&actual.equalsIgnoreCase(expected),message);}
    private static void equal(Object actual,Object expected,String message){require(actual.equals(expected),message);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private void check(String key){checks.put(key,true);}
    @SuppressWarnings("unchecked") private void append(String field,Object value){((List<Object>)measurements.computeIfAbsent(field,key->new ArrayList<>())).add(value);}

    private Path inside(Path path)throws Exception {
        Path normalized=path.toAbsolutePath().normalize();require(normalized.startsWith(root),"Path escaped workspace");
        Path real=normalized.toRealPath();require(real.startsWith(root),"Link escaped workspace");
        for(Path part=normalized;part!=null&&part.startsWith(root);part=part.getParent())require(!Files.isSymbolicLink(part),"Symbolic artifact path refused");
        return normalized;
    }
    private JsonObject read(Path input)throws Exception {
        Path file=inside(input);require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Missing regular JSON");
        long size=Files.size(file);require(size>0&&size<=JSON_LIMIT,"JSON byte cap: "+file.getFileName());
        JsonElement value=JsonParser.parseString(Files.readString(file));require(value.isJsonObject(),"Expected JSON object");
        artifactHashes.put(root.relativize(file).toString(),hash(file,JSON_LIMIT));return value.getAsJsonObject();
    }
    private String hash(Path input,long limit)throws Exception {
        Path file=inside(input);require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Missing regular evidence file");
        long size=Files.size(file);require(size>0&&size<=limit,"Evidence byte cap: "+file.getFileName());
        var digest=MessageDigest.getInstance("SHA-256");long read=0;
        try(InputStream stream=Files.newInputStream(file)){byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1){read+=count;require(read<=limit,"Evidence grew beyond cap");digest.update(buffer,0,count);}}
        require(read==size,"Evidence changed while reading");String value=HexFormat.of().formatHex(digest.digest());
        artifactHashes.put(root.relativize(file).toString(),value);return value;
    }
    private BufferedImage image(Path input)throws Exception {
        Path file=inside(input);require(Files.size(file)>0&&Files.size(file)<=PNG_LIMIT,"PNG byte cap");
        try(var stream=ImageIO.createImageInputStream(file.toFile())) {
            require(stream!=null,"PNG reader unavailable");var readers=ImageIO.getImageReaders(stream);require(readers.hasNext(),"Unreadable actual PNG");var reader=readers.next();
            try {reader.setInput(stream,true,true);require(reader.getFormatName().equalsIgnoreCase("PNG")&&reader.getWidth(0)==960&&reader.getHeight(0)==540,"Actual PNG format/dimensions before allocation");
                BufferedImage image=reader.read(0);require(image!=null&&image.getWidth()==960&&image.getHeight()==540,"Decoded actual dimensions");return image;}
            finally {reader.dispose();}
        }
    }
    private void write(String status,Throwable failure)throws Exception {
        require(!Files.exists(output),"Refusing existing immutable validation");
        Map<String,Object> result=new LinkedHashMap<>();result.put("runId",runId);result.put("sessionNonce",nonce);result.put("uuid",uuid);
        result.put("status",status);result.put("visualAcceptance","NOT_EVALUATED");result.put("manualReviewRequired",true);
        result.put("writtenAtMillis",System.currentTimeMillis());result.put("validatorJavaVersion",System.getProperty("java.runtime.version"));
        result.put("checks",checks);result.put("measurements",measurements);result.put("artifactSha256",artifactHashes);
        result.put("failure",failure==null?null:Map.of("class",failure.getClass().getName(),"message",String.valueOf(failure.getMessage())));
        result.put("limits",Map.of("jsonBytes",JSON_LIMIT,"pngBytes",PNG_LIMIT,"heldFrames",12,"liveMaximum",24,"telemetryMaximum",512,
                "liveMaximumAgeMillis",250,"heldPositionTolerance",.10,"heldAngleTolerance",.03,"livePositionTolerance",.25,"liveAngleTolerance",.08));
        Path temporary=Files.createTempFile(run,".numeric-validation-",".tmp");
        // Default move refuses an existing target; ATOMIC_MOVE permits implementation-specific replacement.
        try {Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(result));require(!Files.exists(output),"Concurrent immutable validation exists");Files.move(temporary,output);}
        finally {Files.deleteIfExists(temporary);}
    }
}
