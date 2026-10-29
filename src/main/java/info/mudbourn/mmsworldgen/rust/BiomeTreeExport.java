package info.mudbourn.mmsworldgen.rust;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

// Flattens vanilla's Climate.RTree structures into the arrays the native search reads.
final class BiomeTreeExport {

    static final int DIMS = 7;

    // Flat nodes of all trees, the biome value of each id and the leaf spans of each tree.
    record Export(
        long[] spans,
        int[] meta,
        int[] children,
        int[] roots,
        List<Holder<Biome>> holders,
        Map<Holder<Biome>, Integer> ids,
        long[][] leafSpans
    ) {
    }

    private final LongArrayList spans = new LongArrayList();
    private final IntArrayList meta = new IntArrayList();
    private final IntArrayList children = new IntArrayList();
    private final List<Holder<Biome>> holders = new ArrayList<>();
    private final Map<Holder<Biome>, Integer> ids = new IdentityHashMap<>();
    private LongArrayList leaves;

    private BiomeTreeExport() {
    }

    // Exports the trees in order; a null tree keeps its index with no search possible.
    static Export export(List<Climate.RTree<Holder<Biome>>> trees) {
        BiomeTreeExport export = new BiomeTreeExport();
        int[] roots = new int[trees.size()];
        long[][] leafSpans = new long[trees.size()][];
        for (int i = 0; i < trees.size(); i++) {
            Climate.RTree<Holder<Biome>> tree = trees.get(i);
            export.leaves = new LongArrayList();
            roots[i] = tree == null ? -1 : export.add(tree.root);
            leafSpans[i] = export.leaves.toLongArray();
        }
        return new Export(
            export.spans.toLongArray(),
            export.meta.toIntArray(),
            export.children.toIntArray(),
            roots,
            export.holders,
            export.ids,
            leafSpans
        );
    }

    private int add(Climate.RTree.Node<Holder<Biome>> node) {
        int id = meta.size() / 3;
        if (node.parameterSpace.length != DIMS) {
            throw new IllegalStateException("biome tree node has " + node.parameterSpace.length + " spans");
        }
        for (Climate.Parameter parameter : node.parameterSpace) {
            spans.add(parameter.min());
            spans.add(parameter.max());
        }
        meta.add(0);
        meta.add(0);
        meta.add(-1);
        if (node instanceof Climate.RTree.Leaf<Holder<Biome>> leaf) {
            int base = id * 3;
            meta.set(base + 2, valueId(leaf.value));
            for (int i = spans.size() - DIMS * 2; i < spans.size(); i++) {
                leaves.add(spans.getLong(i));
            }
            return id;
        }
        Climate.RTree.SubTree<Holder<Biome>> subTree = (Climate.RTree.SubTree<Holder<Biome>>) node;
        int[] childIds = new int[subTree.children.length];
        for (int i = 0; i < childIds.length; i++) {
            childIds[i] = add(subTree.children[i]);
        }
        meta.set(id * 3, children.size());
        meta.set(id * 3 + 1, childIds.length);
        for (int childId : childIds) {
            children.add(childId);
        }
        return id;
    }

    private int valueId(Holder<Biome> holder) {
        Integer existing = ids.get(holder);
        if (existing != null) {
            return existing;
        }
        int id = holders.size();
        holders.add(holder);
        ids.put(holder, id);
        return id;
    }
}
