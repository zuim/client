package org.kamiblue.client.util.combat

import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.EnumCreatureAttribute
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.monster.EntityMob
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Enchantments
import net.minecraft.init.MobEffects
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemSword
import net.minecraft.item.ItemTool
import net.minecraft.util.CombatRules
import net.minecraft.util.DamageSource
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.client.event.KamiEventBus
import org.kamiblue.client.event.SafeClientEvent
import org.kamiblue.client.event.events.ConnectionEvent
import org.kamiblue.client.mixin.extension.attackDamage
import org.kamiblue.client.util.items.swapToSlot
import org.kamiblue.client.util.threads.safeListener
import org.kamiblue.event.listener.listener
import kotlin.math.max
import kotlin.math.round

object CombatUtils {
    private val cachedArmorValues = HashMap<EntityLivingBase, Pair<Float, Float>>()

    fun SafeClientEvent.calcDamageFromPlayer(entity: EntityPlayer, assumeCritical: Boolean = false): Float {
        val itemStack = entity.heldItemMainhand

        var damage = when (val item = itemStack.item) {
            is ItemSword -> item.attackDamage
            is ItemTool -> item.attackDamage
            else -> 1f
        }

        val sharpnessLevel = EnchantmentHelper.getEnchantmentLevel(Enchantments.SHARPNESS, itemStack)
        damage += sharpnessLevel * 0.5f + 0.5f

        if (assumeCritical) damage *= 1.5f
        return calcDamage(player, damage)
    }

    fun SafeClientEvent.calcDamageFromMob(entity: EntityMob): Float {
        var damage = entity.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).attributeValue.toFloat()

        damage += EnchantmentHelper.getModifierForCreature(entity.heldItemMainhand, player.creatureAttribute)

        return calcDamage(player, damage)
    }

    fun calcDamage(entity: EntityLivingBase, damageIn: Float = 100f, source: DamageSource = DamageSource.GENERIC, roundDamage: Boolean = false): Float {
        if (entity is EntityPlayer && entity.isCreative) return 0.0f // Return 0 directly if entity is a player and in creative mode

        val pair = cachedArmorValues[entity] ?: return 0.0f
        var damage = CombatRules.getDamageAfterAbsorb(damageIn, pair.first, pair.second)

        if (source != DamageSource.OUT_OF_WORLD) {
            entity.getActivePotionEffect(MobEffects.RESISTANCE)?.let {
                damage *= max(1.0f - (it.amplifier + 1) * 0.2f, 0.0f)
            }
        }

        if (entity is EntityPlayer) {
            damage *= getProtectionModifier(entity, source)
        }

        return if (roundDamage) round(damage) else damage
    }

    fun getProtectionModifier(entity: EntityPlayer, damageSource: DamageSource): Float {
        var modifier = 0

        for (armor in entity.armorInventoryList.toList()) {
            if (armor.isEmpty) continue // Skip if item stack is empty
            val nbtTagList = armor.enchantmentTagList
            for (i in 0 until nbtTagList.tagCount()) {
                val compoundTag = nbtTagList.getCompoundTagAt(i)

                val id = compoundTag.getInteger("id")
                val level = compoundTag.getInteger("lvl")

                Enchantment.getEnchantmentByID(id)?.let { modifier += it.calcModifierDamage(level, damageSource) }
            }
        }

        modifier = modifier.coerceIn(0, 20)

        return (1.0f - modifier / 25.0f)
    }

    fun SafeClientEvent.equipBestWeapon(preferWeapon: PreferWeapon = PreferWeapon.NONE) {
        var bestSlot = -1
        var maxDamage = 0.0f

        for (i in 0..8) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack.isEmpty) continue

            val item = stack.item

            if (item is ItemSword && (preferWeapon == PreferWeapon.SWORD || preferWeapon == PreferWeapon.NONE)) {
                val damage = item.attackDamage + EnchantmentHelper.getModifierForCreature(stack, EnumCreatureAttribute.UNDEFINED)

                if (damage > maxDamage) {
                    maxDamage = damage
                    bestSlot = i
                }
            } else if (item is ItemTool || item is ItemAxe && (preferWeapon == PreferWeapon.AXE || preferWeapon == PreferWeapon.NONE)) {
                val damage = (item as ItemTool).attackDamage + EnchantmentHelper.getModifierForCreature(stack, EnumCreatureAttribute.UNDEFINED)

                if (damage > maxDamage) {
                    maxDamage = damage
                    bestSlot = i
                }
            }

        }

        if (bestSlot != -1) swapToSlot(bestSlot)
    }

    fun getHealthSmart(entity: EntityLivingBase) = entity.health + entity.absorptionAmount * (entity.health / entity.maxHealth)

    init {
        safeListener<TickEvent.ClientTickEvent> {
            for (entity in world.loadedEntityList) {
                if (entity !is EntityLivingBase) continue
                val armorValue = entity.totalArmorValue.toFloat()
                val toughness = entity.getEntityAttribute(SharedMonsterAttributes.ARMOR_TOUGHNESS).attributeValue.toFloat()

                cachedArmorValues[entity] = armorValue to toughness
            }
        }

        listener<ConnectionEvent.Disconnect> {
            cachedArmorValues.clear()
        }

        KamiEventBus.subscribe(this)
    }

    enum class PreferWeapon {
        SWORD, AXE, NONE
    }
}