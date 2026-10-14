package info.mudbourn.mmsworldgen.budget;

import info.mudbourn.mmsworldgen.mixin.StructurePlacementAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

public final class BudgetPlacement extends RandomSpreadStructurePlacement {

    private final Identifier setId;
    private final CellArbiter arbiter;

    public BudgetPlacement(RandomSpreadStructurePlacement original, Identifier setId, CellArbiter arbiter) {
        super(
            ((StructurePlacementAccessor) original).mmsworldgen$locateOffset(),
            ((StructurePlacementAccessor) original).mmsworldgen$frequencyReductionMethod(),
            ((StructurePlacementAccessor) original).mmsworldgen$frequency(),
            ((StructurePlacementAccessor) original).mmsworldgen$salt(),
            ((StructurePlacementAccessor) original).mmsworldgen$exclusionZone(),
            arbiter.cellSize(),
            budgetSeparation(original, arbiter),
            original.spreadType()
        );
        this.setId = setId;
        this.arbiter = arbiter;
    }

    private static int budgetSeparation(RandomSpreadStructurePlacement original, CellArbiter arbiter) {
        return Math.min(original.separation(), arbiter.cellSize() - 1);
    }

    @Override
    public ChunkPos getPotentialStructureChunk(long seed, int chunkX, int chunkZ) {
        int size = arbiter.cellSize();
        int cellX = Math.floorDiv(chunkX, size);
        int cellZ = Math.floorDiv(chunkZ, size);
        CellArbiter.Result result = arbiter.resultAt(cellX, cellZ);
        if (result.owner() != null && result.owner().id().equals(setId)) {
            return result.start();
        }
        return new ChunkPos(cellX * size - 1, cellZ * size - 1);
    }
}
