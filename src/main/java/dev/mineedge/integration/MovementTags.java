package dev.mineedge.integration;

import dev.mineedge.MineEdge;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Data-pack-owned surface choices; native block collision and callbacks still own geometry/damage. */
public final class MovementTags {
    public static final TagKey<Block> LADDERS = block("ladders");
    public static final TagKey<Block> DRAINPIPES = block("drainpipes");
    public static final TagKey<Block> STEP_UP = block("step_up_surfaces");
    public static final TagKey<Block> STEEP = block("steep_surfaces");
    public static final TagKey<Block> SOFT = block("soft_landing_blocks");
    private static TagKey<Block> block(String name) { return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MineEdge.MOD_ID, name)); }
    private MovementTags() {}
}
