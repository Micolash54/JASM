package dev.micolash.jasm.client;

import dev.micolash.jasm.network.DataCableBlock;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.registry.JasmBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

/** Digging particles follow the cable or panel under the crosshair. */
public final class DataCableClientExtensions implements IClientBlockExtensions {
    @Override
    public boolean addHitEffects(BlockState state, Level level, BlockPos pos, Direction face, ParticleEngine particles) {
        var hit = Minecraft.getInstance().hitResult;
        if (!(level instanceof ClientLevel client) || !(hit instanceof BlockHitResult blockHit)
                || !blockHit.getBlockPos().equals(pos) || !(level.getBlockEntity(pos) instanceof DataCableBlockEntity cable)
                || DataCableBlock.hitPort(cable, hit.getLocation()) == null) return false;
        var point = hit.getLocation().add(face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05);
        particles.add(new TerrainParticle(client, point.x, point.y, point.z, 0, 0, 0,
                JasmBlocks.ACCESS_PORT.get().defaultBlockState(), pos).setPower(0.2F).scale(0.6F));
        return true;
    }
}
