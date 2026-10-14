package info.mudbourn.mmsworldgen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.budget.BudgetRegistry;
import info.mudbourn.mmsworldgen.budget.CellArbiter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldgenCommand {

    private WorldgenCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mmswg")
            .requires(WorldgenCommand::allowed)
            .then(Commands.literal("survey")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                    .executes(ctx -> survey(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
            .then(Commands.literal("cell")
                .executes(ctx -> cell(ctx.getSource())))
            .then(Commands.literal("dump")
                .executes(ctx -> dump(ctx.getSource()))));
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(2)));
    }

    private static BudgetRegistry.Binding binding(CommandSourceStack source) {
        Identifier dimension = source.getLevel().dimension().identifier();
        BudgetRegistry.Binding binding = BudgetRegistry.binding(dimension);
        if (binding == null) {
            source.sendFailure(Component.literal("Dimension " + dimension + " is not managed."));
        }
        return binding;
    }

    private static int[] sourceCell(CommandSourceStack source, CellArbiter arbiter) {
        int size = arbiter.cellSize();
        int chunkX = Math.floorDiv((int) Math.floor(source.getPosition().x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(source.getPosition().z), 16);
        return new int[] {Math.floorDiv(chunkX, size), Math.floorDiv(chunkZ, size)};
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int survey(CommandSourceStack source, int radius) {
        BudgetRegistry.Binding binding = binding(source);
        if (binding == null) {
            return 0;
        }
        CellArbiter arbiter = binding.arbiter();
        int[] origin = sourceCell(source, arbiter);

        int side = radius * 2 + 1;
        CellArbiter.Entry[] owners = new CellArbiter.Entry[side * side];
        int empty = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                CellArbiter.Entry owner = arbiter.resultAt(origin[0] + dx, origin[1] + dz).owner();
                owners[(dz + radius) * side + dx + radius] = owner;
                if (owner == null) {
                    empty++;
                }
            }
        }

        Map<String, Integer> byGroup = new LinkedHashMap<>();
        Map<CellArbiter.Entry, List<Integer>> bySet = new LinkedHashMap<>();
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] != null) {
                byGroup.merge(owners[i].group(), 1, Integer::sum);
                bySet.computeIfAbsent(owners[i], k -> new ArrayList<>()).add(i);
            }
        }

        say(source, String.format(
            Locale.ROOT,
            "Survey r=%d: %d cells, %.1f%% empty",
            radius,
            owners.length,
            100.0 * empty / owners.length
        ));
        byGroup.forEach((group, count) -> say(source, String.format(
            Locale.ROOT,
            "group %s %.1f%%",
            group,
            100.0 * count / owners.length
        )));
        bySet.forEach((entry, cells) -> say(source, String.format(
            Locale.ROOT,
            "set %s %.1f%% nearest-same %s",
            entry.id(),
            100.0 * cells.size() / owners.length,
            nearestSame(cells, side)
        )));
        return 1;
    }

    private static String nearestSame(List<Integer> cells, int side) {
        if (cells.size() < 2) {
            return "n/a";
        }
        double total = 0;
        for (int a : cells) {
            double best = Double.MAX_VALUE;
            for (int b : cells) {
                if (a != b) {
                    double dx = a % side - b % side;
                    double dz = a / side - b / side;
                    best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
                }
            }
            total += best;
        }
        return String.format(Locale.ROOT, "%.1f", total / cells.size());
    }

    private static int cell(CommandSourceStack source) {
        BudgetRegistry.Binding binding = binding(source);
        if (binding == null) {
            return 0;
        }
        int[] origin = sourceCell(source, binding.arbiter());
        CellArbiter.Result result = binding.arbiter().resultAt(origin[0], origin[1]);

        say(source, "Cell " + origin[0] + "," + origin[1]);
        if (result.emptyRoll()) {
            say(source, "empty (fill roll)");
            return 1;
        }
        if (result.owner() == null) {
            say(source, "empty (no candidate passed)");
        } else {
            say(source, "owner " + result.owner().id() + " at chunk "
                + result.start().x + "," + result.start().z);
        }
        for (CellArbiter.Candidate candidate : result.candidates()) {
            say(source, String.format(
                Locale.ROOT,
                "%s w=%.2f novelty=%.2f chunk %d,%d %s",
                candidate.entry().id(),
                candidate.entry().weight(),
                candidate.novelty(),
                candidate.chunk().x,
                candidate.chunk().z,
                candidate.biomeFail() ? "lost: biome" : "won"
            ));
        }
        return 1;
    }

    private static int dump(CommandSourceStack source) {
        BudgetRegistry.Binding binding = binding(source);
        if (binding == null) {
            return 0;
        }
        int[] counts = new int[BudgetRegistry.Status.values().length];
        for (BudgetRegistry.Row row : binding.rows()) {
            counts[row.status().ordinal()]++;
            MmsWorldgen.LOG.info(
                "{} {} group={} weight={}",
                row.status(),
                row.id(),
                row.group(),
                row.weight()
            );
        }
        say(source, "managed " + counts[BudgetRegistry.Status.MANAGED.ordinal()]
            + ", exempt " + counts[BudgetRegistry.Status.EXEMPT.ordinal()]
            + ", unmanaged placement " + counts[BudgetRegistry.Status.UNMANAGED_PLACEMENT.ordinal()]
            + ", skipped " + counts[BudgetRegistry.Status.SKIPPED.ordinal()]
            + " (see server log)");
        return 1;
    }
}
