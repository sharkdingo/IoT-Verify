package cn.edu.nju.Iot_Verify.component.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Extractive task notes; never an operation receipt or a write authorization. */
public record TaskMemory(int schemaVersion, long throughMessageId, String sceneRevision, List<Item> items) {
    public TaskMemory {
        if (schemaVersion != 1 || throughMessageId < 0 || sceneRevision == null || items == null
                || items.size() > 24 || items.stream().mapToInt(i -> i.quote().length()).sum() > 6000
                || items.stream().map(Item::id).distinct().count() != items.size()
                || items.stream().anyMatch(i -> i.sourceMessageId() > throughMessageId)) {
            throw new IllegalArgumentException("Invalid task memory");
        }
        items = List.copyOf(items);
    }

    public record Item(String id, String kind, String scope, long sourceMessageId, String quote) {
        public Item {
            if (id == null || !id.matches("[1-9][0-9]*:[0-9]+") || sourceMessageId <= 0
                    || !id.startsWith(sourceMessageId + ":")
                    || !Set.of("GOAL", "CONSTRAINT", "DECISION", "QUESTION").contains(kind)
                    || !Set.of("SESSION", "SCENE").contains(scope)
                    || quote == null || quote.isBlank() || quote.length() > 600) {
                throw new IllegalArgumentException("Invalid task memory item");
            }
        }
    }

    public List<Item> applicableItems(String scene) {
        return items.stream().filter(i -> "SESSION".equals(i.scope())
                || Objects.equals(sceneRevision, scene)).toList();
    }

    public TaskMemory apply(JsonNode patch, Map<Long, String> sources, long throughId, String scene) {
        fields(patch, Set.of("add", "remove"));
        if (!patch.path("add").isArray() || !patch.path("remove").isArray()
                || patch.path("add").size() > 24 || patch.path("remove").size() > 24
                || throughId <= throughMessageId || !sources.containsKey(throughId)) {
            throw new IllegalArgumentException("Invalid memory patch");
        }
        List<Item> updated = new ArrayList<>(applicableItems(scene));
        Set<String> removed = new HashSet<>();
        for (JsonNode removal : patch.path("remove")) {
            fields(removal, Set.of("itemId", "sourceMessageId", "quote"));
            source(removal, sources);
            String id = text(removal, "itemId");
            if (!removed.add(id) || !updated.removeIf(item -> item.id().equals(id))) {
                throw new IllegalArgumentException("Unknown or repeated memory removal");
            }
        }
        int index = 0;
        for (JsonNode addition : patch.path("add")) {
            fields(addition, Set.of("kind", "scope", "sourceMessageId", "quote"));
            long sourceId = source(addition, sources);
            String quote = text(addition, "quote");
            Item item = new Item(sourceId + ":" + index++, text(addition, "kind"),
                    text(addition, "scope"), sourceId, quote);
            if (updated.stream().noneMatch(old -> old.kind().equals(item.kind())
                    && old.scope().equals(item.scope()) && old.quote().equals(quote))) updated.add(item);
        }
        return new TaskMemory(1, throughId, scene, updated);
    }

    private static long source(JsonNode node, Map<Long, String> sources) {
        JsonNode id = node.path("sourceMessageId");
        String quote = text(node, "quote");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || quote.isBlank() || quote.length() > 600
                || !sources.containsKey(id.longValue()) || !sources.get(id.longValue()).contains(quote)) {
            throw new IllegalArgumentException("Memory must cite exact supplied user words");
        }
        return id.longValue();
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("Invalid memory text");
        return node.path(field).textValue();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            throw new IllegalArgumentException("Invalid memory fields");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!expected.contains(name)) throw new IllegalArgumentException("Unknown memory field");
        });
    }
}
