package dev.heiko.universe.clienttest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Quaterniond;

/** Test-side artifact coordination only; these files never supply client entity state. */
final class DynamicProtocol {
    static final boolean ENABLED=ClientProbeMod.ENABLED&&ClientProbeMod.DYNAMIC;
    static Map<String,Object> envelope(Map<String,Object> payload){
        Map<String,Object> row=new LinkedHashMap<>(payload);
        row.put("runId",ClientProbeMod.RUN_ID);row.put("sessionNonce",ClientProbeMod.SESSION_NONCE);row.put("dynamic",ClientProbeMod.DYNAMIC);
        row.put("runtimeNonce",ClientProbeMod.RUNTIME_NONCE);row.put("writtenAtMillis",System.currentTimeMillis());return row;
    }
    static JsonObject read(Path path) throws Exception {
        if(!Files.isRegularFile(path)||Files.size(path)>1024*1024)throw new IllegalStateException("Missing/oversize sidecar");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static void envelope(JsonObject data,String uuid) {
        if(!data.has("dynamic")||!data.get("dynamic").getAsBoolean()||!ClientProbeMod.RUN_ID.equals(data.get("runId").getAsString())
                ||!ClientProbeMod.SESSION_NONCE.equals(data.get("sessionNonce").getAsString())
                ||!uuid.equals(data.get("uuid").getAsString()))throw new IllegalStateException("Artifact identity mismatch");
        java.util.UUID.fromString(data.get("runtimeNonce").getAsString());
        long age=System.currentTimeMillis()-data.get("writtenAtMillis").getAsLong();
        if(age<0||age>120_000)throw new IllegalStateException("Stale sidecar");
    }
    static double[] array(JsonArray data,int length){
        if(data.size()!=length)throw new IllegalStateException("Bad numeric shape");
        double[] values=new double[length];for(int i=0;i<length;i++){values[i]=data.get(i).getAsDouble();if(!Double.isFinite(values[i]))throw new IllegalStateException("Nonfinite value");}return values;
    }
    static void pose(JsonObject client,JsonObject server,double positionTolerance,double angleTolerance) {
        double[] a=array(client.getAsJsonArray("position"),3),b=array(server.getAsJsonArray("position"),3);
        double distance=Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));
        double[] x=array(client.getAsJsonArray("orientation"),4),y=array(server.getAsJsonArray("orientation"),4);
        Quaterniond q=new Quaterniond(x[0],x[1],x[2],x[3]),r=new Quaterniond(y[0],y[1],y[2],y[3]);
        double qn=q.lengthSquared(),rn=r.lengthSquared();
        if(Math.abs(qn-1)>1e-5||Math.abs(rn-1)>1e-5)throw new IllegalStateException("Nonunit quaternion");
        double angle=2*Math.acos(Math.min(1,Math.abs(q.dot(r))));
        if(distance>positionTolerance||angle>angleTolerance)throw new IllegalStateException("Held actual client/server pose mismatch: distance="+distance+", angle="+angle);
    }
    static String sha(Path file) throws Exception {
        long size=Files.size(file);if(size<1||size>8*1024*1024)throw new IllegalStateException("PNG size cap");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

