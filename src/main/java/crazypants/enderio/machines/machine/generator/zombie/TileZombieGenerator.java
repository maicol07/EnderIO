package crazypants.enderio.machines.machine.generator.zombie;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.enderio.core.api.common.util.ITankAccess;
import com.enderio.core.common.NBTAction;
import com.enderio.core.common.fluid.FluidWrapper;
import com.enderio.core.common.fluid.SmartTank;
import com.enderio.core.common.fluid.SmartTankFluidHandler;
import com.enderio.core.common.util.BlockCoord;

import crazypants.enderio.base.config.Config;
import crazypants.enderio.base.fluid.Fluids;
import crazypants.enderio.base.fluid.SmartTankFluidMachineHandler;
import crazypants.enderio.base.machine.baselegacy.SlotDefinition;
import crazypants.enderio.base.machine.modes.IoMode;
import crazypants.enderio.base.network.PacketHandler;
import crazypants.enderio.base.power.PowerDistributor;
import crazypants.enderio.machines.init.MachineObject;
import crazypants.enderio.machines.machine.generator.AbstractGeneratorEntity;
import info.loenwind.autosave.annotations.Storable;
import info.loenwind.autosave.annotations.Store;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import static crazypants.enderio.machines.capacitor.CapacitorKey.ZOMBIE_POWER_BUFFER;
import static crazypants.enderio.machines.capacitor.CapacitorKey.ZOMBIE_POWER_GEN;
import static crazypants.enderio.machines.capacitor.CapacitorKey.ZOMBIE_POWER_LOSS;

@Storable
public class TileZombieGenerator extends AbstractGeneratorEntity implements ITankAccess.IExtendedTankAccess, IHasNutrientTank {

  private static int IO_MB_TICK = 250;

  @Store
  final SmartTank tank = new SmartTank(Fluids.NUTRIENT_DISTILLATION.getFluid(), Fluid.BUCKET_VOLUME * 2);

  int tickPerBucketOfFuel = Config.zombieGeneratorTicksPerBucketFuel;

  private boolean tanksDirty;
  @Store(NBTAction.UPDATE)
  private boolean active = false;
  private PowerDistributor powerDis;

  private int ticksRemaingFuel;
  private boolean inPause;

  public TileZombieGenerator() {
    super(new SlotDefinition(0, 0, 0), ZOMBIE_POWER_LOSS, ZOMBIE_POWER_BUFFER, ZOMBIE_POWER_GEN);
    tank.setTileEntity(this);
    tank.setCanDrain(false);
  }

  @Override
  public @Nonnull String getMachineName() {
    return MachineObject.block_zombie_generator.getUnlocalisedName();
  }


  @Override
  public boolean supportsMode(@Nullable EnumFacing faceHit, @Nullable IoMode mode) {
    return mode != IoMode.PUSH && mode != IoMode.PUSH_PULL;
  }

  @Override
  protected boolean doPull(@Nullable EnumFacing dir) {
    boolean res = super.doPull(dir);
    if (dir != null && tank.getFluidAmount() < tank.getCapacity()) {
      if (FluidWrapper.transfer(world, getPos().offset(dir), dir.getOpposite(), tank, IO_MB_TICK) > 0) {
        setTanksDirty();
      }
    }
    return res;
  }

  @Override
  public boolean isMachineItemValidForSlot(int i, ItemStack itemstack) {
    return false;
  }

  @Override
  public boolean isActive() {
    return active;
  }
  
  @Override
  public void onNeighborBlockChange(IBlockState state, World worldIn, BlockPos posIn, Block blockIn, BlockPos fromPos) {
    super.onNeighborBlockChange(state, worldIn, posIn, blockIn, fromPos);
    if(powerDis != null) {
      powerDis.neighboursChanged();
    }
  }

  @Override
  protected boolean processTasks(boolean redstoneCheck) {
    boolean res = false;

    if(!redstoneCheck) {
      if(active) {
        active = false;
        res = true;
      }
      return res;
    } else {

      boolean isActive = generateEnergy();
      if(isActive != active) {
        active = isActive;
        res = true;
      }

      if(getEnergyStored() >= getMaxEnergyStored()) {
        inPause = true;
      }

      transmitEnergy();
    }

    if(tanksDirty) {
      PacketHandler.sendToAllAround(new PacketNutrientTank(this), this);
      tanksDirty = false;
    }

    return res;
  }

  private boolean generateEnergy() {

    //once full, don't start again until we have drained 10 seconds worth of power to prevent
    //flickering on and off constantly when powering a machine that draws less than this produces
    if (inPause && getEnergyStored() >= (getMaxEnergyStored() - (getPowerUsePerTick() * 200)) && getEnergyStored() > (getMaxEnergyStored() / 8)) {
      return false;
    }
    inPause = false;

    if(tank.getFluidAmount() < getActivationAmount()) {
      return false;
    }
    
    
    ticksRemaingFuel--;
    if(ticksRemaingFuel <= 0) {
      tank.removeFluidAmount(1);
      ticksRemaingFuel = tickPerBucketOfFuel/1000;
    }
    setEnergyStored(getEnergyStored() + getPowerUsePerTick());
    return true;
  }
  
  int getActivationAmount() {
    return (int) (tank.getCapacity() * 0.7f);
  }

  private boolean transmitEnergy() {
    if(getEnergyStored() <= 0) {
      return false;
    }
    if(powerDis == null) {
      powerDis = new PowerDistributor(BlockCoord.get(this));
    }
    int transmitted = powerDis.transmitEnergy(world, Math.min(getPowerUsePerTick() * 2, getEnergyStored()));
    setEnergyStored(getEnergyStored() - transmitted);
    return transmitted > 0;
  }

  public int getFluidStored(EnumFacing from) {
    return tank.getFluidAmount();
  }
  
  @Override
  public boolean shouldRenderInPass(int pass) {
    return pass == 1;
  }

  @Override
  public FluidTank getInputTank(FluidStack forFluidType) {
    if (forFluidType != null && forFluidType.getFluid() == Fluids.NUTRIENT_DISTILLATION.getFluid()) {
      return tank;
    }
    return null;
  }

  @Override
  public FluidTank[] getOutputTanks() {
    return new FluidTank[0];
  }

  @Override
  public void setTanksDirty() {
    tanksDirty = true;
  }

  @Override
  public SmartTank getNutrientTank() {
    return tank;
  }

  @SuppressWarnings("null")
  @Override
  @Nonnull
  public List<ITankData> getTankDisplayData() {
    return Collections.<ITankData> singletonList(new ITankData() {

      @Override
      @Nonnull
      public EnumTankType getTankType() {
        return EnumTankType.INPUT;
      }

      @Override
      @Nullable
      public FluidStack getContent() {
        return tank.getFluid();
      }

      @Override
      public int getCapacity() {
        return tank.getCapacity();
      }
    });
  }

  private SmartTankFluidHandler smartTankFluidHandler;

  protected SmartTankFluidHandler getSmartTankFluidHandler() {
    if (smartTankFluidHandler == null) {
      smartTankFluidHandler = new SmartTankFluidMachineHandler(this, tank);
    }
    return smartTankFluidHandler;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getCapability(Capability<T> capability, EnumFacing facingIn) {
    if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
      return (T) getSmartTankFluidHandler().get(facingIn);
    }
    return super.getCapability(capability, facingIn);
  }
}