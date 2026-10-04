package dev.heiko.universe.api.effect;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
/** Catalog size never allocates active effects. Construct once during content registration. */
public final class EffectCatalog {
    private final Map<String,EffectDefinition> definitions;
    public EffectCatalog(Collection<EffectDefinition> input, int maxDefinitions) {
        Objects.requireNonNull(input);
        if(maxDefinitions<=0 || input.size()>maxDefinitions) throw new IllegalArgumentException("catalog limit");
        var result=new HashMap<String,EffectDefinition>();
        for(var definition:input) {
            Objects.requireNonNull(definition);
            if(result.putIfAbsent(definition.id(),definition)!=null)
                throw new IllegalArgumentException("duplicate definition: "+definition.id());
        }
        definitions=Map.copyOf(result);
    }
    public int size() { return definitions.size(); }
    public EffectDefinition require(String id) {
        var result=definitions.get(id);
        if(result==null) throw new IllegalArgumentException("unknown effect: "+id);
        return result;
    }
}
