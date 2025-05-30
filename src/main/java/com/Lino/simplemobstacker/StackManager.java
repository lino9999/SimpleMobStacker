package com.Lino.simplemobstacker;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestisce le operazioni di stacking per mob e item
 */
public class StackManager {

    private final SimpleMobStacker plugin;
    private final Map<UUID, StackData> stackData = new ConcurrentHashMap<>();

    public StackManager(SimpleMobStacker plugin) {
        this.plugin = plugin;
    }

    /**
     * Ottiene i dati di stack per un'entità
     */
    public StackData getStackData(UUID entityId) {
        return stackData.get(entityId);
    }

    /**
     * Imposta i dati di stack per un'entità
     */
    public void setStackData(UUID entityId, StackData data) {
        if (data == null) {
            stackData.remove(entityId);
        } else {
            stackData.put(entityId, data);
        }
    }

    /**
     * Ottiene la dimensione dello stack per un'entità
     */
    public int getStackSize(Entity entity) {
        StackData data = stackData.get(entity.getUniqueId());
        if (data != null) {
            return data.getSize();
        }

        // Se è un item, ritorna la quantità dell'ItemStack
        if (entity instanceof Item) {
            return ((Item) entity).getItemStack().getAmount();
        }

        return 1;
    }

    /**
     * Imposta la dimensione dello stack per un'entità
     */
    public void setStackSize(Entity entity, int size) {
        if (size <= 1) {
            removeStack(entity);
            return;
        }

        StackData data = stackData.computeIfAbsent(entity.getUniqueId(),
                k -> new StackData(entity.getType(), size));
        data.setSize(size);
        data.updateLastStackTime();
    }

    /**
     * Rimuove un'entità dal sistema di stacking
     */
    public void removeStack(Entity entity) {
        stackData.remove(entity.getUniqueId());
        entity.setCustomName(null);
        entity.setCustomNameVisible(false);
    }

    /**
     * Verifica se un'entità può essere stackata
     */
    public boolean canStack(Entity entity) {
        StackData data = stackData.get(entity.getUniqueId());
        if (data == null) return true;

        // Evita stacking troppo frequente
        return System.currentTimeMillis() - data.getLastStackTime() >= 1000;
    }

    /**
     * Pulisce i dati obsoleti
     */
    public void cleanup() {
        stackData.entrySet().removeIf(entry -> {
            UUID entityId = entry.getKey();

            // Cerca l'entità in tutti i mondi
            for (var world : plugin.getServer().getWorlds()) {
                Entity entity = world.getEntity(entityId);
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    return false;
                }
            }

            return true;
        });
    }

    /**
     * Ottiene statistiche sul sistema di stacking
     */
    public StackStats getStats() {
        int totalStacks = 0;
        int totalEntities = 0;
        int mobStacks = 0;
        int itemStacks = 0;

        for (StackData data : stackData.values()) {
            totalStacks++;
            totalEntities += data.getSize();

            if (data.isMobStack()) {
                mobStacks++;
            } else {
                itemStacks++;
            }
        }

        return new StackStats(totalStacks, totalEntities, mobStacks, itemStacks);
    }

    /**
     * Pulisce tutti i dati di stacking
     */
    public void clearAll() {
        stackData.clear();
    }

    /**
     * Classe per contenere i dati di uno stack
     */
    public static class StackData {
        private final org.bukkit.entity.EntityType type;
        private int size;
        private long lastStackTime;
        private ItemStack itemStack; // Per gli item

        public StackData(org.bukkit.entity.EntityType type, int size) {
            this.type = type;
            this.size = size;
            this.lastStackTime = System.currentTimeMillis();
        }

        public org.bukkit.entity.EntityType getType() {
            return type;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getLastStackTime() {
            return lastStackTime;
        }

        public void updateLastStackTime() {
            this.lastStackTime = System.currentTimeMillis();
        }

        public boolean isMobStack() {
            return type.isAlive();
        }

        public ItemStack getItemStack() {
            return itemStack;
        }

        public void setItemStack(ItemStack itemStack) {
            this.itemStack = itemStack;
        }
    }

    /**
     * Classe per le statistiche
     */
    public static class StackStats {
        private final int totalStacks;
        private final int totalEntities;
        private final int mobStacks;
        private final int itemStacks;

        public StackStats(int totalStacks, int totalEntities, int mobStacks, int itemStacks) {
            this.totalStacks = totalStacks;
            this.totalEntities = totalEntities;
            this.mobStacks = mobStacks;
            this.itemStacks = itemStacks;
        }

        public int getTotalStacks() {
            return totalStacks;
        }

        public int getTotalEntities() {
            return totalEntities;
        }

        public int getMobStacks() {
            return mobStacks;
        }

        public int getItemStacks() {
            return itemStacks;
        }
    }
}