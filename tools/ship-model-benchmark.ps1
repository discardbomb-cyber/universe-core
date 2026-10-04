param([string]$JdkPath = '')
$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
if (-not $JdkPath) { $JdkPath = Join-Path $workspacePath '.tooling/java21/jdk-21.0.12.1+1' }
$compilerPath = Join-Path $JdkPath 'bin/javac.exe'
$runtimePath = Join-Path $JdkPath 'bin/java.exe'
if (-not (Test-Path -LiteralPath $compilerPath)) { throw 'Pass -JdkPath pointing to Java 21.' }
$compilerVersion = & $compilerPath -version 2>&1
if ($LASTEXITCODE -ne 0 -or "$compilerVersion" -notmatch '^javac 21\.') { throw 'Benchmark requires a Java 21 compiler.' }
# Isolated direct javac: no Gradle daemon, project build output or Minecraft dependencies.
$benchDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('universe-ship-model-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $benchDirectory | Out-Null
$sourcePath = Join-Path $benchDirectory 'ShipModelBenchmark.java'
@'
import dev.heiko.universe.ships.*;
import java.util.*;

public class ShipModelBenchmark {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        ShipId id = new ShipId(new UUID(0,42));
        ShipStructure hull = new ShipStructure(id,16384);
        List<ShipSection> sections = new ArrayList<>();
        List<ShipStructure.Summary> summaries = new ArrayList<>();
        long started = System.nanoTime();
        for(int x=0;x<64;x++) for(int y=0;y<16;y++) for(int z=0;z<16;z++) {
            int blocks=4096-(x==0||x==63?15:16)*(y==0||y==15?15:16)*(z==0||z==15?15:16);
            if(blocks==0) continue;
            var section=new ShipSection(id,x-32,y,z);
            var summary=new ShipStructure.Summary(0,blocks,blocks*.5);
            sections.add(section);summaries.add(summary);hull.update(section,summary);
        }
        long setupNanos=System.nanoTime()-started;
        require(hull.occupiedBlocks()==1173512 && hull.knownSections()==4232,"Shell count");
        var context=new ShipPose.SpaceContext("normal","milky_way","home");
        var orbit=new OrbitState(id,"home",context,Vec3.ZERO,new Vec3(1,0,0),new Vec3(0,0,1),10000,0,0,.01,Rotation.IDENTITY);
        double checksum=0;
        for(int i=0;i<20000;i++) checksum+=orbit.at(i*.05).position().x();
        var before=hull.workCounters();started=System.nanoTime();
        for(int i=0;i<100000;i++) checksum+=orbit.at(i*.05).position().x();
        long motionNanos=System.nanoTime()-started;
        require(before.equals(hull.workCounters()),"Motion touched structure");
        started=System.nanoTime();
        for(int i=0;i<32;i++) {
            var old=summaries.get(i);
            var changed=new ShipStructure.Summary(1,old.occupiedBlocks()-1,old.mass()-.5);
            hull.update(sections.get(i),changed);hull.update(sections.get(i),changed);
        }
        long editNanos=System.nanoTime()-started;var after=hull.workCounters();
        require(after.sectionLookups()-before.sectionLookups()==64,"Changed section lookups");
        require(after.aggregateUpdates()-before.aggregateUpdates()==32,"Changed section aggregates");
        require(after.snapshotEntriesCopied()==0,"Unexpected full snapshot");
        require(hull.occupiedBlocks()==1173480 && hull.mass()==586740,"Aggregate totals");
        var d=new DockingController(16);
        var a=new DockPort(new UUID(0,1),id,context,Vec3.ZERO,Rotation.IDENTITY,"standard",2,0,true);
        var b=new DockPort(new UUID(0,2),new ShipId(new UUID(0,43)),context,Vec3.ZERO,Rotation.IDENTITY,"standard",2,0,true);
        d.putPort(a);d.putPort(b);UUID first=new UUID(2,0);
        var original=d.reserve(first,a.id(),b.id(),id,0,100,true);
        for(int i=1;i<16;i++) d.reserve(new UUID(2,i),a.id(),b.id(),id,0,100,true);
        for(int i=16;i<10016;i++) {
            require(d.reserve(new UUID(2,i),a.id(),b.id(),id,0,100,true).code()==DockingController.Code.CAPACITY,"Capacity");
            require(original.equals(d.reserve(first,a.id(),b.id(),id,0,100,true)),"Replay lost");
        }
        require(d.operationHistorySize()==16,"History growth");
        System.out.println("java="+System.getProperty("java.version"));
        System.out.println("os="+System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        System.out.println("shell=1024x256x256; conceptualBlocks=1173512; summaries=4232; interiorEmptySections=12152");
        System.out.println("motionSamples=100000; hullLookups=0; aggregateUpdates=0; snapshotEntriesCopied=0");
        System.out.println("changedSections=32; requests=64; sectionLookups=64; aggregateUpdates=32; finalBlocks="+hull.occupiedBlocks());
        System.out.println("historyLimit=16; historySize="+d.operationHistorySize()+"; floodRejected=10000; originalReplayRetained=true");
        System.out.printf(Locale.ROOT,"observationalMs: setup=%.3f; motion=%.3f; edits=%.3f; checksum=%.6f%n",setupNanos/1e6,motionNanos/1e6,editNanos/1e6,checksum);
        System.out.println("PASS pure-model invariants; no Minecraft blocks allocated or scanned");
    }
}
'@ | Set-Content -LiteralPath $sourcePath -Encoding utf8
$modelSources = @(Get-ChildItem -LiteralPath (Join-Path $workspacePath 'src/main/java/dev/heiko/universe/ships') -Filter '*.java' | ForEach-Object FullName)
# On this sandbox --release 21 triggers a ct.sym close error after successful compilation.
# With an explicitly checked JDK 21, source/target 21 uses the same Java 21 API baseline.
& $compilerPath -source 21 -target 21 -encoding UTF-8 -d $benchDirectory @modelSources $sourcePath
if ($LASTEXITCODE -ne 0) { throw 'Standalone javac failed.' }
& $runtimePath -cp $benchDirectory ShipModelBenchmark
if ($LASTEXITCODE -ne 0) { throw 'Standalone benchmark failed.' }
# Leave temporary files for inspection; no recursive filesystem deletion.
Write-Output "Temporary benchmark classes: $benchDirectory"
