package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterial
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent

/**
 * <b>Role.</b> Custom armor damage reduction for NPC girls
 * ([AbstractGirlNpcEntity]). The constructor precomputes the vanilla
 * armor/toughness table per slot+material into [ArmorStats];
 * [onLivingDamage] then applies the full armor formula (reduction,
 * toughness, Protection/Thorns/Fire/Blast/Feather-Falling/Projectile
 * enchantments) to damage dealt to NPC girls, and reflects Thorns damage back
 * to the attacker.
 *
 * <b>Pitfall.</b> The table is keyed by `slot+material` strings and
 * defaulted to `3` reduction / `0` toughness on a miss — an
 * unseeded entry silently weakens/strengthens armor instead of failing.
 *
 * Port notes: 1.12 `LivingDamageEvent` is `LivingDamageEvent.Pre`
 * (`newDamage`); armor materials are `Holder<ArmorMaterial>` (1.12 `GOLD`/`CHAIN`
 * are `ArmorMaterials.GOLD`/`CHAIN`; materials added after 1.12 such as
 * netherite fall back to the 3/0 default exactly like an unseeded 1.12 entry);
 * the damage-source checks are damage-type tags; enchantment levels are read
 * through the enchantment registry.
 */
open class DamageCalculation {
    init {
        ArmorStats.calculateDamage(EquipmentSlot.HEAD, ArmorMaterials.LEATHER, 1, 0)
        ArmorStats.calculateDamage(EquipmentSlot.HEAD, ArmorMaterials.GOLD, 2, 0)
        ArmorStats.calculateDamage(EquipmentSlot.HEAD, ArmorMaterials.CHAIN, 2, 0)
        ArmorStats.calculateDamage(EquipmentSlot.HEAD, ArmorMaterials.IRON, 2, 0)
        ArmorStats.calculateDamage(EquipmentSlot.HEAD, ArmorMaterials.DIAMOND, 3, 3)
        ArmorStats.calculateDamage(EquipmentSlot.CHEST, ArmorMaterials.LEATHER, 3, 0)
        ArmorStats.calculateDamage(EquipmentSlot.CHEST, ArmorMaterials.GOLD, 5, 0)
        ArmorStats.calculateDamage(EquipmentSlot.CHEST, ArmorMaterials.CHAIN, 5, 0)
        ArmorStats.calculateDamage(EquipmentSlot.CHEST, ArmorMaterials.IRON, 6, 0)
        ArmorStats.calculateDamage(EquipmentSlot.CHEST, ArmorMaterials.DIAMOND, 8, 3)
        ArmorStats.calculateDamage(EquipmentSlot.LEGS, ArmorMaterials.LEATHER, 2, 0)
        ArmorStats.calculateDamage(EquipmentSlot.LEGS, ArmorMaterials.GOLD, 3, 0)
        ArmorStats.calculateDamage(EquipmentSlot.LEGS, ArmorMaterials.CHAIN, 4, 0)
        ArmorStats.calculateDamage(EquipmentSlot.LEGS, ArmorMaterials.IRON, 5, 0)
        ArmorStats.calculateDamage(EquipmentSlot.LEGS, ArmorMaterials.DIAMOND, 6, 3)
        ArmorStats.calculateDamage(EquipmentSlot.FEET, ArmorMaterials.LEATHER, 1, 0)
        ArmorStats.calculateDamage(EquipmentSlot.FEET, ArmorMaterials.GOLD, 1, 0)
        ArmorStats.calculateDamage(EquipmentSlot.FEET, ArmorMaterials.CHAIN, 1, 0)
        ArmorStats.calculateDamage(EquipmentSlot.FEET, ArmorMaterials.IRON, 2, 0)
        ArmorStats.calculateDamage(EquipmentSlot.FEET, ArmorMaterials.DIAMOND, 3, 3)
    }

    @SubscribeEvent
    open fun onLivingDamage(event: LivingDamageEvent.Pre) {
        val girl = event.entity
        if (girl is AbstractGirlNpcEntity) {
            val armorStacks = arrayOf(
                girl.inventory.getStackInSlot(2),
                girl.inventory.getStackInSlot(3),
                girl.inventory.getStackInSlot(4),
                girl.inventory.getStackInSlot(5),
            )
            val armorItems = ArrayList<ArmorItem>()
            val toughnessItems = ArrayList<ItemStack>()

            for (stack in armorStacks) {
                val item = stack.item
                if (item is ArmorItem) {
                    armorItems.add(item)
                    toughnessItems.add(stack)
                }
            }

            if (armorItems.size != 0) {
                val source = event.source
                var armorReduction = 0
                var toughness = 0
                if (!source.`is`(DamageTypeTags.BYPASSES_ARMOR)) {
                    for (armorItem in armorItems) {
                        armorReduction += ArmorStats.getArmorDamageReduction(armorItem.equipmentSlot, armorItem.material)
                        toughness += ArmorStats.getArmorToughness(armorItem.equipmentSlot, armorItem.material)
                    }
                }

                var damage = event.newDamage
                damage *= 1.0f - Math.min(20.0f, Math.max(armorReduction / 5.0f, armorReduction - 4.0f * damage / (toughness + 8.0f))) / 25.0f
                var thornsDamage = 0.0f
                val level = girl.level()

                for (protectionStack in toughnessItems) {
                    val protectionLevel = enchantmentLevel(level, Enchantments.PROTECTION, protectionStack)
                    damage -= protectionLevel * 0.04f * damage
                    val thornsLevel = enchantmentLevel(level, Enchantments.THORNS, protectionStack)
                    thornsDamage += if (Reference.RANDOM.nextFloat() < 0.15f * thornsLevel) Reference.RANDOM.nextFloat() * 4.0f + 1.0f else 0.0f
                    thornsDamage = Math.min(4.0f, thornsDamage)
                    if (source.`is`(DamageTypeTags.IS_FIRE)) {
                        val fireProtLevel = enchantmentLevel(level, Enchantments.FIRE_PROTECTION, protectionStack)
                        damage -= fireProtLevel * 0.08f * damage
                    }

                    if (source.`is`(DamageTypeTags.IS_EXPLOSION)) {
                        val blastProtLevel = enchantmentLevel(level, Enchantments.BLAST_PROTECTION, protectionStack)
                        damage -= blastProtLevel * 0.08f * damage
                    }

                    if (source.`is`(DamageTypes.FALL)) {
                        val featherFallLevel = enchantmentLevel(level, Enchantments.FEATHER_FALLING, protectionStack)
                        damage -= featherFallLevel * 0.12f * damage
                    }

                    if (source.`is`(DamageTypeTags.IS_PROJECTILE)) {
                        val projectileProtLevel = enchantmentLevel(level, Enchantments.PROJECTILE_PROTECTION, protectionStack)
                        damage -= projectileProtLevel * 0.08f * damage
                    }
                }

                // 1.12 `source instanceof EntityDamageSource && getTrueSource() != null`
                val attacker = source.entity
                if (thornsDamage > 0.0f && attacker != null) {
                    attacker.hurt(girl.damageSources().thorns(girl), thornsDamage)
                }

                event.newDamage = damage
            }
        }
    }

    /** 1.12 `EnchantmentHelper.getEnchantmentLevel(Enchantment, stack)`. */
    private fun enchantmentLevel(level: Level, key: ResourceKey<Enchantment>, stack: ItemStack): Int {
        val holder = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolder(key).orElse(null) ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }

    object ArmorStats {
        @JvmField
        var armorStats: HashMap<String, Array<Int>> = HashMap()

        private fun key(slot: EquipmentSlot, material: Holder<ArmorMaterial>): String = slot.toString() + material.registeredName

        @JvmStatic
        fun getArmorDamageReduction(slot: EquipmentSlot, material: Holder<ArmorMaterial>): Int {
            return armorStats[key(slot, material)]?.get(0) ?: 3
        }

        @JvmStatic
        fun getArmorToughness(slot: EquipmentSlot, material: Holder<ArmorMaterial>): Int {
            return armorStats[key(slot, material)]?.get(1) ?: 0
        }

        @JvmStatic
        fun calculateDamage(slot: EquipmentSlot, material: Holder<ArmorMaterial>, damage: Int, toughness: Int) {
            armorStats[key(slot, material)] = arrayOf(damage, toughness)
        }
    }
}
