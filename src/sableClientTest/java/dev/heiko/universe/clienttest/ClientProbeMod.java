package dev.heiko.universe.clienttest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.neoforged.fml.common.Mod;

/** Opt-in development fixture; no client types in the common entry point. */
@Mod(ClientProbeMod.ID)
public final class ClientProbeMod {
    public static final String ID = "universe_sable_client_test";
    public static final String RUN_ID = System.getProperty("universe.clientProbe.runId", "");
    public static final String SESSION_NONCE = System.getProperty("universe.clientProbe.nonce", "");
    public static final String RUNTIME_NONCE = UUID.randomUUID().toString();
    public static final long RUNTIME_STARTED_MILLIS = System.currentTimeMillis();
    public static final boolean ENABLED = Boolean.getBoolean("universe.clientProbe.enabled")
            && RUN_ID.matches("[A-Za-z0-9_-]{1,64}");
    public static final boolean DYNAMIC = Boolean.getBoolean("universe.clientProbe.dynamic");
    public static final String NAME = "Universe client probe " + RUN_ID;
    public static final long TIMEOUT_NANOS = 120_000_000_000L;
    private static final Map<String, Path> CLAIMED = new HashMap<>();

    static synchronized Path claim(String role) throws IOException {
        if (CLAIMED.containsKey(role)) return CLAIMED.get(role);
        if (!SESSION_NONCE.matches("[A-Za-z0-9_-]{16,80}"))
            throw new IOException("A fresh supervisor session nonce is required");
        Path parent = Path.of(System.getProperty("universe.clientProbe.artifacts", "client-probe-artifacts"))
                .toAbsolutePath();
        Files.createDirectories(parent);
        Path run = parent.resolve(RUN_ID);
        if (role.equals("server")) {
            // Atomic directory claim: reject ANY previous runId, even an empty abandoned directory.
            Files.createDirectory(run);
            atomicJson(run.resolve("session.json"), Map.of("runId", RUN_ID, "sessionNonce", SESSION_NONCE,
                    "serverRuntimeNonce", RUNTIME_NONCE, "dynamic", DYNAMIC, "createdAtMillis", System.currentTimeMillis()), false);
        } else if (role.equals("client")) {
            var session = JsonParser.parseString(Files.readString(run.resolve("session.json"))).getAsJsonObject();
            long age = System.currentTimeMillis() - session.get("createdAtMillis").getAsLong();
            if (!RUN_ID.equals(session.get("runId").getAsString())
                    || !SESSION_NONCE.equals(session.get("sessionNonce").getAsString())
                    || session.get("dynamic").getAsBoolean()!=DYNAMIC || age < 0 || age > 120_000)
                throw new IOException("Session manifest is stale or does not match the supervisor nonce");
        } else throw new IOException("Unknown role");
        Path directory = run.resolve(role);
        Files.createDirectory(directory); // Reject a restarted role; never adopt or overwrite its old artifacts.
        CLAIMED.put(role, directory);
        return directory;
    }

    static void atomicJson(Path target, Map<String, Object> data, boolean replace) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".probe-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(data));
            if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else {
                if (Files.exists(target)) throw new IOException("Refusing existing artifact " + target);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static void report(String role, String phase, String status, Map<String, Object> details) throws IOException {
        Path root = claim(role);
        Map<String, Object> report = new LinkedHashMap<>(details);
        report.put("runId", RUN_ID);
        report.put("dynamic", DYNAMIC);
        report.put("sessionNonce", SESSION_NONCE);
        report.put("runtimeNonce", RUNTIME_NONCE);
        report.put("pid", ProcessHandle.current().pid());
        report.put("javaVersion", System.getProperty("java.runtime.version"));
        report.put("runtimeStartedAtMillis", RUNTIME_STARTED_MILLIS);
        report.put("writtenAtMillis", System.currentTimeMillis());
        report.put("phase", phase);
        report.put("status", status);
        report.put("visualAcceptance", "NOT_EVALUATED");
        report.put("timeoutSeconds", 120);
        atomicJson(root.resolve("report.json"), report, true);
    }
}
