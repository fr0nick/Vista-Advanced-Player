package com.fr0nick.vista_advanced_player.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.mehvahdjukaar.vista.common.connection.AbstractGridAccess;
import net.mehvahdjukaar.vista.common.connection.ConnectionType;
import net.mehvahdjukaar.vista.common.connection.GridAccessor;
import net.mehvahdjukaar.vista.common.connection.GridTile;
import net.mehvahdjukaar.vista.common.connection.RectFinder;
import net.mehvahdjukaar.vista.common.connection.RectSelection;
import net.mehvahdjukaar.moonlight.api.util.math.Rect2D;
import net.mehvahdjukaar.moonlight.api.util.math.Vec2i;
import net.mehvahdjukaar.moonlight.api.util.math.MthUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Mixin(TVBlock.class)
public class TvBlockMixin {

    @WrapOperation(
        method = "setPlacedBy", 
        at = {
            @At(value = "INVOKE", target = "Lnet/mehvahdjukaar/vista/common/tv/TVBlock;enlargeConnection(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"),
            @At(value = "INVOKE", target = "Lnet/mehvahdjukaar/vista/common/connection/IConnectedBlock;enlargeConnection(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V")
        },
        require = 1
    )
    private void advancedPlayer$customEnlargeConnection(net.mehvahdjukaar.vista.common.tv.TVBlock instance, BlockState state, Level level, BlockPos pos, Operation<Void> original) {
        int maxSize = instance.maxConnectedSize();
        if (maxSize > 1) {
            boolean squareOnly = instance.squareAspectRatio();
            AbstractGridAccess realGrid = instance.createGridAccess(level, pos, state);
            Direction facing = state.getValue(net.mehvahdjukaar.vista.common.tv.TVBlock.FACING);
            
            GridAccessor fakeGrid = new GridAccessor() {
                @Override
                @NotNull
                public GridTile getAt(Vec2i key) {
                    GridTile tile = realGrid.getAt(key);
                    if (key.x() != 0 || key.y() != 0) {
                        boolean isPowered = tile.powerState() != null && tile.powerState().isOn();
                        boolean hasCassette = false;
                        
                        BlockPos targetPos = MthUtils.relativePos(pos, facing, key.x(), key.y(), 0);
                        BlockState targetState = level.getBlockState(targetPos);
                        
                        if (targetState.getBlock() instanceof net.mehvahdjukaar.vista.common.tv.TVBlock tvBlock) {
                            BlockEntity master = tvBlock.findMasterBlockEntity(level, targetPos, targetState);
                            if (master instanceof net.mehvahdjukaar.vista.common.tv.TVBlockEntity tvEnt) {
                                hasCassette = !tvEnt.isEmpty();
                            }
                        }
                        
                        if (isPowered || hasCassette) {
                            return GridTile.EMPTY;
                        }
                    }
                    return tile;
                }
                
                @Override
                public void setAt(Vec2i key, @Nullable ConnectionType type, boolean setPower) {
                    realGrid.setAt(key, type, setPower);
                }
                
                @Override
                public void planBeMove(@Nullable Rect2D fromRec, Rect2D toRec) {
                    realGrid.planBeMove(fromRec, toRec);
                }
            };
            
            Rect2D old = RectFinder.findMaxRect(realGrid, Vec2i.ZERO, squareOnly);
            RectSelection newRec = RectFinder.findMaxExpandedRect(fakeGrid, Vec2i.ZERO, maxSize, squareOnly);
            
            realGrid.transform(old, newRec.selection(), newRec.touchedRect());
            realGrid.applyChanges();

            Rect2D sel = newRec.selection();
            for (int y = sel.y(); y < sel.y() + sel.height(); y++) {
                for (int x = sel.x(); x < sel.x() + sel.width(); x++) {
                    Vec2i key = new Vec2i(x, y);
                    BlockPos targetPos = MthUtils.relativePos(pos, facing, key.x(), key.y(), 0);
                    BlockState targetState = level.getBlockState(targetPos);
                    
                    if (targetState.is(instance)) {
                        if (!instance.shouldHaveBlockEntity(targetState)) {
                            level.removeBlockEntity(targetPos);
                        }
                    }
                }
            }
        }
    }
}