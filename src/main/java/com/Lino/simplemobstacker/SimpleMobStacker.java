package com.Lino.simplemobstacker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class SimpleMobStacker extends JavaPlugin implements Listener, TabCompleter {

    private final Map<UUID, Integer> stackedMobs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stackedItems = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStackTime = new ConcurrentHashMap<>();

    // Configuration values
    private int stackRadius;
    private int playerRadius;
    private int maxStackSize;
    private int itemStackRadius;
    private int maxItemStackSize;
    private boolean stackNaturalMobs;
    private boolean stackSpawnerMobs;
    private boolean stackItems;
    private boolean stackEggMobs;
    private boolean requireSameAge;
    private boolean requireSameHealth;
    private List<String> stackableTypes;
    private List<String> blacklistedWorlds;
    private boolean enableStackAbsorption;
    private int cleanupInterval;
    private boolean debugMode;
    private int maxStacksPerChunk;
    private boolean useDisplayEntities;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfiguration();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("simplemobstacker").setTabCompleter(this);

        // Task per controllare e stackare mob/items per chunk caricati
        new BukkitRunnable() {
            @Override
            public void run() {
                performStackingTask();
            }
        }.runTaskTimer(this, 100L, 100L);

        // Task di cleanup per rimuovere entries obsolete
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupMaps();
            }
        }.runTaskTimer(this, 20L * cleanupInterval, 20L * cleanupInterval);

        getLogger().info("SimpleMobStacker v" + getDescription().getVersion() + " abilitato!");
    }

    @Override
    public void onDisable() {
        // Cleanup di tutte le display names prima di disabilitare
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.hasMetadata("SimpleMobStacker")) {
                    entity.setCustomName(null);
                    entity.setCustomNameVisible(false);
                }
            }
        }
        getLogger().info("SimpleMobStacker disabilitato!");
    }

    private void reloadConfiguration() {
        reloadConfig();
        FileConfiguration config = getConfig();

        stackRadius = config.getInt("stack-radius", 8);
        playerRadius = config.getInt("player-radius", 50);
        maxStackSize = config.getInt("max-stack-size", 64);
        itemStackRadius = config.getInt("item-stack-radius", 5);
        maxItemStackSize = config.getInt("max-item-stack-size", 1000);
        stackNaturalMobs = config.getBoolean("stack-natural-mobs", true);
        stackSpawnerMobs = config.getBoolean("stack-spawner-mobs", true);
        stackEggMobs = config.getBoolean("stack-egg-mobs", true);
        stackItems = config.getBoolean("stack-items", true);
        enableStackAbsorption = config.getBoolean("enable-stack-absorption", true);
        requireSameAge = config.getBoolean("require-same-age", true);
        requireSameHealth = config.getBoolean("require-same-health", false);
        cleanupInterval = config.getInt("cleanup-interval", 300);
        debugMode = config.getBoolean("debug-mode", false);
        maxStacksPerChunk = config.getInt("max-stacks-per-chunk", 50);
        useDisplayEntities = config.getBoolean("use-display-entities", true);

        stackableTypes = config.getStringList("stackable-types");
        blacklistedWorlds = config.getStringList("blacklisted-worlds");

        if (stackableTypes.isEmpty()) {
            stackableTypes = getDefaultStackableTypes();
        }
    }

    private List<String> getDefaultStackableTypes() {
        return Arrays.asList(
                "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER",
                "ENDERMAN", "WITCH", "SLIME", "MAGMA_CUBE", "BLAZE",
                "COW", "SHEEP", "PIG", "CHICKEN", "RABBIT", "VILLAGER",
                "IRON_GOLEM", "SNOWMAN", "GUARDIAN", "ELDER_GUARDIAN"
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("simplemobstacker")) return false;

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("simplemobstacker.admin")) {
                    sender.sendMessage("§cNon hai il permesso per usare questo comando!");
                    return true;
                }
                reloadConfiguration();
                sender.sendMessage("§aSimpleMobStacker ricaricato con successo!");
                break;

            case "clear":
                if (!sender.hasPermission("simplemobstacker.admin")) {
                    sender.sendMessage("§cNon hai il permesso per usare questo comando!");
                    return true;
                }
                clearAllStacks();
                sender.sendMessage("§aTutti gli stack sono stati rimossi!");
                break;

            case "stats":
                if (!sender.hasPermission("simplemobstacker.stats")) {
                    sender.sendMessage("§cNon hai il permesso per usare questo comando!");
                    return true;
                }
                showStats(sender);
                break;

            case "debug":
                if (!sender.hasPermission("simplemobstacker.admin")) {
                    sender.sendMessage("§cNon hai il permesso per usare questo comando!");
                    return true;
                }
                debugMode = !debugMode;
                sender.sendMessage("§aDebug mode: " + (debugMode ? "§aON" : "§cOFF"));
                break;

            default:
                sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("simplemobstacker.admin")) {
                completions.addAll(Arrays.asList("reload", "clear", "debug"));
            }
            if (sender.hasPermission("simplemobstacker.stats")) {
                completions.add("stats");
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== SimpleMobStacker Help ===");
        if (sender.hasPermission("simplemobstacker.admin")) {
            sender.sendMessage("§e/sms reload §7- Ricarica la configurazione");
            sender.sendMessage("§e/sms clear §7- Rimuove tutti gli stack");
            sender.sendMessage("§e/sms debug §7- Toggle debug mode");
        }
        if (sender.hasPermission("simplemobstacker.stats")) {
            sender.sendMessage("§e/sms stats §7- Mostra statistiche");
        }
    }

    private void showStats(CommandSender sender) {
        int totalMobStacks = stackedMobs.size();
        int totalMobs = stackedMobs.values().stream().mapToInt(Integer::intValue).sum();
        int totalItemStacks = stackedItems.size();
        int totalItems = stackedItems.values().stream().mapToInt(Integer::intValue).sum();

        sender.sendMessage("§6=== SimpleMobStacker Stats ===");
        sender.sendMessage("§eMob Stacks: §f" + totalMobStacks + " §7(Total: " + totalMobs + ")");
        sender.sendMessage("§eItem Stacks: §f" + totalItemStacks + " §7(Total: " + totalItems + ")");
        sender.sendMessage("§eMemory Usage: §f" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576 + " MB");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        if (blacklistedWorlds.contains(entity.getWorld().getName())) return;

        boolean shouldStack = shouldStackSpawnReason(reason);
        if (!shouldStack || !isStackableType(entity)) return;

        if (!isPlayerNearby(entity.getLocation())) return;

        // Marca l'entità per lo stacking
        entity.setMetadata("SimpleMobStacker", new FixedMetadataValue(this, true));

        // Ritarda lo stacking per evitare problemi con altri plugin
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (entity.isValid() && !entity.isDead()) {
                tryStackWithNearby(entity);
            }
        }, 5L);
    }

    private boolean shouldStackSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        switch (reason) {
            case NATURAL:
                return stackNaturalMobs;
            case SPAWNER:
                return stackSpawnerMobs;
            case SPAWNER_EGG:
            case EGG:
                return stackEggMobs;
            default:
                return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!stackItems) return;

        Item item = event.getEntity();
        if (blacklistedWorlds.contains(item.getWorld().getName())) return;
        if (!isPlayerNearby(item.getLocation())) return;

        item.setMetadata("SimpleMobStacker", new FixedMetadataValue(this, true));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (item.isValid() && !item.isDead()) {
                tryStackItemsWithNearby(item);
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        Item item = event.getItem();

        if (!stackedItems.containsKey(item.getUniqueId())) return;

        int virtualStackSize = stackedItems.get(item.getUniqueId());
        ItemStack itemStack = item.getItemStack();

        event.setCancelled(true);

        // Gestisci il pickup virtuale
        int given = giveItemsToPlayer(player, itemStack, virtualStackSize);

        if (given >= virtualStackSize) {
            stackedItems.remove(item.getUniqueId());
            item.remove();
        } else {
            int remaining = virtualStackSize - given;
            stackedItems.put(item.getUniqueId(), remaining);
            updateItemDisplayName(item, remaining);
        }

        if (debugMode && given > 0) {
            player.sendMessage("§7[Debug] Raccolti " + given + "x " + itemStack.getType().name());
        }
    }

    private int giveItemsToPlayer(Player player, ItemStack baseItem, int amount) {
        int maxStackSize = baseItem.getMaxStackSize();
        int remaining = amount;

        while (remaining > 0) {
            ItemStack toGive = baseItem.clone();
            toGive.setAmount(Math.min(remaining, maxStackSize));

            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(toGive);

            if (!overflow.isEmpty()) {
                int notGiven = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
                return amount - (remaining - (toGive.getAmount() - notGiven));
            }

            remaining -= toGive.getAmount();
        }

        return amount;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        UUID entityId = entity.getUniqueId();

        if (!stackedMobs.containsKey(entityId)) return;

        int stackSize = stackedMobs.get(entityId);
        stackedMobs.remove(entityId);
        lastStackTime.remove(entityId);

        // Se c'è più di un mob nello stack, spawna i rimanenti
        if (stackSize > 1) {
            Location loc = entity.getLocation();
            Entity newEntity = loc.getWorld().spawnEntity(loc, entity.getType());

            // Copia attributi importanti
            if (entity instanceof Ageable && newEntity instanceof Ageable) {
                ((Ageable) newEntity).setAge(((Ageable) entity).getAge());
            }

            if (entity instanceof LivingEntity && newEntity instanceof LivingEntity) {
                LivingEntity oldLiving = (LivingEntity) entity;
                LivingEntity newLiving = (LivingEntity) newEntity;

                // Copia equipment se presente
                if (oldLiving.getEquipment() != null && newLiving.getEquipment() != null) {
                    newLiving.getEquipment().setArmorContents(oldLiving.getEquipment().getArmorContents().clone());
                    newLiving.getEquipment().setItemInMainHand(oldLiving.getEquipment().getItemInMainHand());
                    newLiving.getEquipment().setItemInOffHand(oldLiving.getEquipment().getItemInOffHand());
                }
            }

            // Imposta il nuovo stack size
            int newStackSize = stackSize - 1;
            stackedMobs.put(newEntity.getUniqueId(), newStackSize);
            newEntity.setMetadata("SimpleMobStacker", new FixedMetadataValue(this, true));
            updateDisplayName(newEntity, newStackSize);

            // Moltiplica solo i drop del mob morto (non tutti)
            multiplyDrops(event, 1);
        }
    }

    private void multiplyDrops(EntityDeathEvent event, int multiplier) {
        if (multiplier <= 1) return;

        List<ItemStack> originalDrops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();

        for (ItemStack drop : originalDrops) {
            ItemStack multipliedDrop = drop.clone();
            multipliedDrop.setAmount(drop.getAmount() * multiplier);
            event.getDrops().add(multipliedDrop);
        }

        event.setDroppedExp(event.getDroppedExp() * multiplier);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        // Rimuovi dalle mappe le entità che si trovano in chunk scaricati
        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            UUID id = entity.getUniqueId();
            stackedMobs.remove(id);
            stackedItems.remove(id);
            lastStackTime.remove(id);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!debugMode) return;
        if (!event.getPlayer().hasPermission("simplemobstacker.debug")) return;

        Entity entity = event.getRightClicked();
        if (stackedMobs.containsKey(entity.getUniqueId())) {
            int size = stackedMobs.get(entity.getUniqueId());
            event.getPlayer().sendMessage("§7[Debug] Stack size: " + size);
        }
    }

    private void performStackingTask() {
        for (World world : Bukkit.getWorlds()) {
            if (blacklistedWorlds.contains(world.getName())) continue;

            // Controlla solo i chunk caricati con player vicini
            for (Player player : world.getPlayers()) {
                Chunk centerChunk = player.getLocation().getChunk();
                int chunkRadius = (playerRadius / 16) + 1;

                for (int x = -chunkRadius; x <= chunkRadius; x++) {
                    for (int z = -chunkRadius; z <= chunkRadius; z++) {
                        Chunk chunk = world.getChunkAt(centerChunk.getX() + x, centerChunk.getZ() + z);
                        if (!chunk.isLoaded()) continue;

                        processChunkEntities(chunk);
                    }
                }
            }
        }
    }

    private void processChunkEntities(Chunk chunk) {
        Map<EntityType, List<Entity>> entitiesByType = new HashMap<>();
        int currentStacks = 0;

        // Raggruppa entità per tipo
        for (Entity entity : chunk.getEntities()) {
            if (!entity.hasMetadata("SimpleMobStacker")) continue;

            if (entity instanceof LivingEntity && !(entity instanceof Player) && isStackableType(entity)) {
                entitiesByType.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
                if (stackedMobs.containsKey(entity.getUniqueId())) currentStacks++;
            } else if (stackItems && entity instanceof Item) {
                entitiesByType.computeIfAbsent(entity.getType(), k -> new ArrayList<>()).add(entity);
            }
        }

        // Processa ogni tipo di entità
        for (Map.Entry<EntityType, List<Entity>> entry : entitiesByType.entrySet()) {
            List<Entity> entities = entry.getValue();

            // Limita il numero di stack per chunk
            if (currentStacks >= maxStacksPerChunk) break;

            for (Entity entity : entities) {
                if (!entity.isValid() || entity.isDead()) continue;

                if (entity instanceof Item) {
                    tryStackItemsWithNearby((Item) entity);
                } else {
                    tryStackWithNearby(entity);
                    if (stackedMobs.containsKey(entity.getUniqueId())) currentStacks++;
                }

                if (currentStacks >= maxStacksPerChunk) break;
            }
        }
    }

    private void tryStackWithNearby(Entity entity) {
        // Evita stacking troppo frequente
        Long lastStack = lastStackTime.get(entity.getUniqueId());
        if (lastStack != null && System.currentTimeMillis() - lastStack < 1000) return;

        List<Entity> nearbyEntities = entity.getNearbyEntities(stackRadius, stackRadius, stackRadius).stream()
                .filter(e -> canStackTogether(entity, e))
                .collect(Collectors.toList());

        if (nearbyEntities.isEmpty()) return;

        // Trova l'entità principale (quella con lo stack più grande)
        Entity mainEntity = entity;
        int mainStackSize = stackedMobs.getOrDefault(entity.getUniqueId(), 1);

        for (Entity nearby : nearbyEntities) {
            int nearbySize = stackedMobs.getOrDefault(nearby.getUniqueId(), 1);
            if (nearbySize > mainStackSize) {
                mainEntity = nearby;
                mainStackSize = nearbySize;
            }
        }

        if (!entity.equals(mainEntity)) {
            nearbyEntities.add(entity);
        }

        // Assorbi gli stack
        int totalStack = mainStackSize;
        List<Entity> toRemove = new ArrayList<>();

        for (Entity nearby : nearbyEntities) {
            if (nearby.equals(mainEntity)) continue;

            int nearbySize = stackedMobs.getOrDefault(nearby.getUniqueId(), 1);

            if (totalStack + nearbySize <= maxStackSize) {
                totalStack += nearbySize;
                toRemove.add(nearby);
            } else if (enableStackAbsorption && totalStack < maxStackSize) {
                int canAbsorb = maxStackSize - totalStack;
                totalStack = maxStackSize;

                int remaining = nearbySize - canAbsorb;
                if (remaining > 1) {
                    stackedMobs.put(nearby.getUniqueId(), remaining);
                    updateDisplayName(nearby, remaining);
                } else {
                    toRemove.add(nearby);
                }
                break;
            }
        }

        // Rimuovi entità assorbite
        for (Entity rem : toRemove) {
            stackedMobs.remove(rem.getUniqueId());
            lastStackTime.remove(rem.getUniqueId());
            rem.remove();
        }

        // Aggiorna il main stack
        if (totalStack > 1) {
            stackedMobs.put(mainEntity.getUniqueId(), totalStack);
            lastStackTime.put(mainEntity.getUniqueId(), System.currentTimeMillis());
            updateDisplayName(mainEntity, totalStack);
        }
    }

    private void tryStackItemsWithNearby(Item item) {
        if (!item.isValid() || item.isDead()) return;

        List<Item> nearbyItems = item.getNearbyEntities(itemStackRadius, itemStackRadius, itemStackRadius).stream()
                .filter(e -> e instanceof Item && canStackItemsTogether(item, (Item) e))
                .map(e -> (Item) e)
                .collect(Collectors.toList());

        if (nearbyItems.isEmpty()) return;

        // Trova l'item principale
        Item mainItem = item;
        int mainStackSize = stackedItems.getOrDefault(item.getUniqueId(), item.getItemStack().getAmount());

        for (Item nearby : nearbyItems) {
            int nearbySize = stackedItems.getOrDefault(nearby.getUniqueId(), nearby.getItemStack().getAmount());
            if (nearbySize > mainStackSize) {
                mainItem = nearby;
                mainStackSize = nearbySize;
            }
        }

        if (!item.equals(mainItem)) {
            nearbyItems.add(item);
        }

        // Assorbi gli stack
        int totalStack = mainStackSize;
        List<Item> toRemove = new ArrayList<>();

        for (Item nearby : nearbyItems) {
            if (nearby.equals(mainItem)) continue;

            int nearbySize = stackedItems.getOrDefault(nearby.getUniqueId(), nearby.getItemStack().getAmount());

            if (totalStack + nearbySize <= maxItemStackSize) {
                totalStack += nearbySize;
                toRemove.add(nearby);
            } else if (totalStack < maxItemStackSize) {
                int canAbsorb = maxItemStackSize - totalStack;
                totalStack = maxItemStackSize;

                int remaining = nearbySize - canAbsorb;
                if (remaining > 0) {
                    stackedItems.put(nearby.getUniqueId(), remaining);
                    updateItemDisplayName(nearby, remaining);
                } else {
                    toRemove.add(nearby);
                }
                break;
            }
        }

        // Rimuovi item assorbiti
        for (Item rem : toRemove) {
            stackedItems.remove(rem.getUniqueId());
            rem.remove();
        }

        // Aggiorna il main stack
        if (totalStack > mainItem.getItemStack().getAmount()) {
            stackedItems.put(mainItem.getUniqueId(), totalStack);
            updateItemDisplayName(mainItem, totalStack);
        }
    }

    private boolean canStackTogether(Entity entity1, Entity entity2) {
        if (!entity1.getType().equals(entity2.getType())) return false;
        if (!isStackableType(entity1) || !isStackableType(entity2)) return false;
        if (!(entity1 instanceof Mob) || !(entity2 instanceof Mob)) return false;

        Mob mob1 = (Mob) entity1;
        Mob mob2 = (Mob) entity2;

        // Non stackare mob in combattimento
        if (mob1.getTarget() != null || mob2.getTarget() != null) return false;

        // Controlla età se richiesto
        if (requireSameAge && entity1 instanceof Ageable && entity2 instanceof Ageable) {
            Ageable age1 = (Ageable) entity1;
            Ageable age2 = (Ageable) entity2;
            if (age1.isAdult() != age2.isAdult()) return false;
        }

        // Controlla salute se richiesto
        if (requireSameHealth && entity1 instanceof LivingEntity && entity2 instanceof LivingEntity) {
            LivingEntity liv1 = (LivingEntity) entity1;
            LivingEntity liv2 = (LivingEntity) entity2;
            double healthDiff = Math.abs(liv1.getHealth() - liv2.getHealth());
            if (healthDiff > 1.0) return false;
        }

        // Non stackare mob con nomi custom non generati dal plugin
        String name1 = mob1.getCustomName();
        String name2 = mob2.getCustomName();

        boolean isStackName1 = name1 != null && name1.contains("x ") && name1.startsWith("§");
        boolean isStackName2 = name2 != null && name2.contains("x ") && name2.startsWith("§");

        if ((name1 != null && !isStackName1) || (name2 != null && !isStackName2)) return false;

        // Controlla distanza verticale
        return Math.abs(entity1.getLocation().getY() - entity2.getLocation().getY()) <= 3;
    }

    private boolean canStackItemsTogether(Item item1, Item item2) {
        ItemStack stack1 = item1.getItemStack();
        ItemStack stack2 = item2.getItemStack();

        if (!stack1.isSimilar(stack2)) return false;

        // Controlla distanza verticale
        if (Math.abs(item1.getLocation().getY() - item2.getLocation().getY()) > 2) return false;

        // Non stackare item con nomi custom
        String name1 = item1.getCustomName();
        String name2 = item2.getCustomName();

        boolean isStackName1 = name1 != null && name1.contains("x ") && name1.startsWith("§");
        boolean isStackName2 = name2 != null && name2.contains("x ") && name2.startsWith("§");

        return (name1 == null || isStackName1) && (name2 == null || isStackName2);
    }

    private boolean isStackableType(Entity entity) {
        return stackableTypes.contains(entity.getType().name());
    }

    private boolean isPlayerNearby(Location location) {
        return location.getWorld().getPlayers().stream()
                .anyMatch(player -> player.getLocation().distance(location) <= playerRadius);
    }

    private void updateDisplayName(Entity entity, int stackSize) {
        if (stackSize > 1) {
            if (useDisplayEntities && entity instanceof LivingEntity) {
                Component displayName = Component.text(stackSize + "x ", NamedTextColor.YELLOW)
                        .append(Component.text(formatEntityName(entity.getType().name()), NamedTextColor.WHITE));
                entity.customName(displayName);
            } else {
                entity.setCustomName("§e" + stackSize + "x §f" + formatEntityName(entity.getType().name()));
            }
            entity.setCustomNameVisible(true);
        } else {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        }
    }

    private void updateItemDisplayName(Item item, int stackSize) {
        if (stackSize > item.getItemStack().getAmount()) {
            String itemName = formatEntityName(item.getItemStack().getType().name());
            if (useDisplayEntities) {
                Component displayName = Component.text(stackSize + "x ", NamedTextColor.AQUA)
                        .append(Component.text(itemName, NamedTextColor.WHITE));
                item.customName(displayName);
            } else {
                item.setCustomName("§b" + stackSize + "x §f" + itemName);
            }
            item.setCustomNameVisible(true);
        } else {
            item.setCustomName(null);
            item.setCustomNameVisible(false);
        }
    }

    private String formatEntityName(String name) {
        return name.toLowerCase().replace("_", " ");
    }

    private void cleanupMaps() {
        // Rimuovi entità non più valide dalle mappe
        cleanupMap(stackedMobs);
        cleanupMap(stackedItems);
        cleanupMap(lastStackTime);

        if (debugMode) {
            getLogger().info("[Debug] Cleanup completato. Mobs: " + stackedMobs.size() + ", Items: " + stackedItems.size());
        }
    }

    private void cleanupMap(Map<UUID, ?> map) {
        map.entrySet().removeIf(entry -> {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEntity(entry.getKey()) != null) {
                    Entity entity = world.getEntity(entry.getKey());
                    if (entity != null && entity.isValid() && !entity.isDead()) {
                        return false;
                    }
                }
            }
            return true;
        });
    }

    private void clearAllStacks() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.hasMetadata("SimpleMobStacker")) {
                    entity.setCustomName(null);
                    entity.setCustomNameVisible(false);
                    entity.removeMetadata("SimpleMobStacker", this);
                }
            }
        }
        stackedMobs.clear();
        stackedItems.clear();
        lastStackTime.clear();
    }
}