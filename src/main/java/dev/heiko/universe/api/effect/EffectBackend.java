package dev.heiko.universe.api.effect;
import java.util.List;
/**
 * Optional client adapter, with no Photon or Minecraft linkage in the public API.
 * Synchronize to the complete admitted set: stop missing ids, retain matching ids, start new ids.
 * Backend must honor supplied LOD/cost limits and seed; it must not tick unseen galaxy content.
 * Call from the backend's owning render thread. Manager does not invoke this interface itself.
 */
public interface EffectBackend extends AutoCloseable {
    void synchronize(long tick, List<ActiveEffect> admitted);
    @Override void close();
}
