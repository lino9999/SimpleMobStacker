package com.Lino.simplemobstacker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleMobStacker extends JavaPlugin implements Listener {

    private Map<UUID, Integer> stackedMobs = new ConcurrentHashMap<>();
    private Map<UUID, Integer> stackedItems = new ConcurrentHashMap<>();
    private int stackRadius;
    private int playerRadius;
    private int maxStackSize;
    private int itemStackRadius;
    private int maxItemStackSize;
    private boolean stackNaturalMobs;
    private boolean stackSpawnerMobs;
    private boolean stackItems;
    private List<String> stackableTypes;
    private boolean enableStackAbsorption;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfiguration();

        getServer().getPluginManager().registerEvents(this, this);

        // Task per controllare e stackare i mob ogni 5 secondi
        new BukkitRunnable() {
            @Override
            public void run() {
                stackNearbyMobs();
                if (stackItems) {
                    stackNearbyItems();
                }
            }
        }.runTaskTimer(this, 100L, 100L);

        getLogger().info("SimpleMobStacker Plugin abilitato!");
    }

    @Override
    public void onDisable() {
        getLogger().info("SimpleMobStacker Plugin disabilitato!");
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
        stackItems = config.getBoolean("stack-items", true);
        enableStackAbsorption = config.getBoolean("enable-stack-absorption", true);
        stackableTypes = config.getStringList("stackable-types");

        if (stackableTypes.isEmpty()) {
            stackableTypes = Arrays.asList(
                    "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN",
                    "COW", "SHEEP", "PIG", "CHICKEN", "SLIME"
            );
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("simplemobstacker")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("simplemobstacker.admin")) {
                    sender.sendMessage("§cNon hai il permesso per usare questo comando!");
                    return true;
                }

                reloadConfiguration();
                sender.sendMessage("§aSimpleMobStacker ricaricato con successo!");
                return true;
            } else {
                sender.sendMessage("§eUso: /simplemobstacker reload");
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) return;

        Entity entity = event.getEntity();
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        // Controlla se il tipo di spawn è abilitato
        boolean shouldStack = false;
        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL && stackNaturalMobs) {
            shouldStack = true;
        } else if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER && stackSpawnerMobs) {
            shouldStack = true;
        }

        if (!shouldStack || !isStackableType(entity)) {
            return;
        }

        // Controlla se c'è un player vicino
        if (!isPlayerNearby(entity.getLocation())) {
            return;
        }

        // Cerca mob simili nelle vicinanze per stackare immediatamente
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (entity.isValid() && !entity.isDead()) {
                tryStackWithNearby(entity);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (event.isCancelled() || !stackItems) return;

        Item item = event.getEntity();

        // Controlla se c'è un player vicino
        if (!isPlayerNearby(item.getLocation())) {
            return;
        }

        // Cerca item simili nelle vicinanze per stackare immediatamente
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (item.isValid() && !item.isDead()) {
                tryStackItemsWithNearby(item);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (event.isCancelled()) return;

        Item item = event.getItem();
        Player player = event.getPlayer();

        // Controlla se questo item è uno stack virtuale
        if (stackedItems.containsKey(item.getUniqueId())) {
            int virtualStackSize = stackedItems.get(item.getUniqueId());
            ItemStack itemStack = item.getItemStack();

            // Annulla l'evento di pickup normale
            event.setCancelled(true);

            // Crea un nuovo ItemStack con la quantità corretta
            ItemStack fullStack = itemStack.clone();

            // Aggiungi gli item all'inventario del giocatore
            int maxStackSize = fullStack.getMaxStackSize();
            int remaining = virtualStackSize;

            while (remaining > 0) {
                int amountToGive = Math.min(remaining, maxStackSize);
                fullStack.setAmount(amountToGive);

                // Prova ad aggiungere all'inventario
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(fullStack.clone());

                if (!leftover.isEmpty()) {
                    // Se l'inventario è pieno, droppa gli item rimanenti
                    for (ItemStack leftoverStack : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftoverStack);
                    }
                    break;
                }

                remaining -= amountToGive;
            }

            // Rimuovi l'item dal mondo e dalla mappa degli stack
            stackedItems.remove(item.getUniqueId());
            item.remove();

            // Messaggio opzionale per il debug
            // player.sendMessage("§aRaccolti " + virtualStackSize + "x " + itemStack.getType().name());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        if (!stackedMobs.containsKey(entity.getUniqueId())) {
            return;
        }

        int stackSize = stackedMobs.get(entity.getUniqueId());
        stackedMobs.remove(entity.getUniqueId());

        // Moltiplica i drop
        List<ItemStack> originalDrops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();

        for (ItemStack drop : originalDrops) {
            ItemStack multipliedDrop = drop.clone();
            multipliedDrop.setAmount(drop.getAmount() * stackSize);
            event.getDrops().add(multipliedDrop);
        }

        // Moltiplica l'esperienza
        event.setDroppedExp(event.getDroppedExp() * stackSize);
    }

    private void stackNearbyMobs() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!isStackableType(entity) || !entity.isValid() || entity.isDead()) {
                    continue;
                }

                if (!isPlayerNearby(entity.getLocation())) {
                    continue;
                }

                tryStackWithNearby(entity);
            }
        }
    }

    private void tryStackWithNearby(Entity entity) {
        Location loc = entity.getLocation();
        List<Entity> nearbyEntities = new ArrayList<>();

        // Trova tutti i mob stackabili nelle vicinanze, inclusi quelli già stackati
        for (Entity nearby : entity.getNearbyEntities(stackRadius, stackRadius, stackRadius)) {
            if (canStackTogether(entity, nearby)) {
                nearbyEntities.add(nearby);
            }
        }

        if (!nearbyEntities.isEmpty()) {
            // Trova l'entità con lo stack più grande per renderla il "master stack"
            Entity mainEntity = entity;
            int mainStackSize = stackedMobs.getOrDefault(entity.getUniqueId(), 1);

            for (Entity nearby : nearbyEntities) {
                int nearbySize = stackedMobs.getOrDefault(nearby.getUniqueId(), 1);
                if (nearbySize > mainStackSize) {
                    mainEntity = nearby;
                    mainStackSize = nearbySize;
                }
            }

            // Calcola il totale di tutti gli stack che si possono assorbire
            int totalStack = mainStackSize;
            List<Entity> toRemove = new ArrayList<>();

            // Aggiungi l'entità originale alla lista se non è quella principale
            if (!entity.getUniqueId().equals(mainEntity.getUniqueId())) {
                nearbyEntities.add(entity);
            }

            // Assorbi tutti gli stack vicini nel master stack
            for (Entity nearby : nearbyEntities) {
                if (nearby.getUniqueId().equals(mainEntity.getUniqueId())) continue;

                int nearbySize = stackedMobs.getOrDefault(nearby.getUniqueId(), 1);

                // Controlla se possiamo ancora assorbire questo stack
                if (totalStack + nearbySize <= maxStackSize) {
                    totalStack += nearbySize;
                    toRemove.add(nearby);
                    stackedMobs.remove(nearby.getUniqueId());
                } else {
                    // Se non possiamo assorbire completamente, assorbi quello che possiamo
                    int canAbsorb = maxStackSize - totalStack;
                    if (canAbsorb > 0) {
                        totalStack = maxStackSize;

                        // Riduci lo stack dell'entità vicina
                        int remainingSize = nearbySize - canAbsorb;
                        if (remainingSize > 1) {
                            stackedMobs.put(nearby.getUniqueId(), remainingSize);
                            updateDisplayName(nearby, remainingSize);
                        } else {
                            stackedMobs.remove(nearby.getUniqueId());
                            nearby.setCustomName(null);
                            nearby.setCustomNameVisible(false);
                        }
                    }
                    break; // Abbiamo raggiunto il limite massimo
                }
            }

            // Rimuovi le entità completamente assorbite
            for (Entity toRem : toRemove) {
                toRem.remove();
            }

            // Aggiorna il master stack
            if (totalStack > 1) {
                stackedMobs.put(mainEntity.getUniqueId(), totalStack);
                updateDisplayName(mainEntity, totalStack);
            } else {
                // Se il totale è 1, rimuovi dai tracked stacks
                stackedMobs.remove(mainEntity.getUniqueId());
                mainEntity.setCustomName(null);
                mainEntity.setCustomNameVisible(false);
            }
        }
    }

    private void stackNearbyItems() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item)) continue;

                Item item = (Item) entity;
                if (!item.isValid() || item.isDead()) continue;

                if (!isPlayerNearby(item.getLocation())) {
                    continue;
                }

                tryStackItemsWithNearby(item);
            }
        }
    }

    private void tryStackItemsWithNearby(Item item) {
        List<Item> nearbyItems = new ArrayList<>();

        // Trova tutti gli item stackabili nelle vicinanze
        for (Entity nearby : item.getNearbyEntities(itemStackRadius, itemStackRadius, itemStackRadius)) {
            if (nearby instanceof Item && canStackItemsTogether(item, (Item) nearby)) {
                nearbyItems.add((Item) nearby);
            }
        }

        if (!nearbyItems.isEmpty()) {
            // Trova l'item con lo stack più grande per renderlo il "master stack"
            Item mainItem = item;
            int mainStackSize = stackedItems.getOrDefault(item.getUniqueId(), item.getItemStack().getAmount());

            for (Item nearby : nearbyItems) {
                int nearbySize = stackedItems.getOrDefault(nearby.getUniqueId(), nearby.getItemStack().getAmount());
                if (nearbySize > mainStackSize) {
                    mainItem = nearby;
                    mainStackSize = nearbySize;
                }
            }

            // Calcola il totale di tutti gli stack che si possono assorbire
            int totalStack = mainStackSize;
            List<Item> toRemove = new ArrayList<>();

            // Aggiungi l'item originale alla lista se non è quello principale
            if (!item.getUniqueId().equals(mainItem.getUniqueId())) {
                nearbyItems.add(item);
            }

            // Assorbi tutti gli stack vicini nel master stack
            for (Item nearby : nearbyItems) {
                if (nearby.getUniqueId().equals(mainItem.getUniqueId())) continue;

                int nearbySize = stackedItems.getOrDefault(nearby.getUniqueId(), nearby.getItemStack().getAmount());

                // Controlla se possiamo ancora assorbire questo stack
                if (totalStack + nearbySize <= maxItemStackSize) {
                    totalStack += nearbySize;
                    toRemove.add(nearby);
                    stackedItems.remove(nearby.getUniqueId());
                } else {
                    // Se non possiamo assorbire completamente, assorbi quello che possiamo
                    int canAbsorb = maxItemStackSize - totalStack;
                    if (canAbsorb > 0) {
                        totalStack = maxItemStackSize;

                        // Riduci lo stack dell'item vicino
                        int remainingSize = nearbySize - canAbsorb;
                        if (remainingSize > 0) {
                            stackedItems.put(nearby.getUniqueId(), remainingSize);
                            updateItemDisplayName(nearby, remainingSize);
                        } else {
                            toRemove.add(nearby);
                            stackedItems.remove(nearby.getUniqueId());
                        }
                    }
                    break; // Abbiamo raggiunto il limite massimo
                }
            }

            // Rimuovi gli item completamente assorbiti
            for (Item toRem : toRemove) {
                toRem.remove();
            }

            // Aggiorna il master stack
            if (totalStack > mainItem.getItemStack().getAmount()) {
                stackedItems.put(mainItem.getUniqueId(), totalStack);
                updateItemDisplayName(mainItem, totalStack);
            } else if (totalStack <= mainItem.getItemStack().getAmount()) {
                // Se il totale è uguale o minore dell'amount originale, rimuovi dai tracked stacks
                stackedItems.remove(mainItem.getUniqueId());
                mainItem.setCustomName(null);
                mainItem.setCustomNameVisible(false);
            }
        }
    }

    private boolean canStackTogether(Entity entity1, Entity entity2) {
        if (!entity1.getType().equals(entity2.getType())) {
            return false;
        }

        if (!isStackableType(entity1) || !isStackableType(entity2)) {
            return false;
        }

        // Controlla se sono entrambi mob (non player, item, ecc.)
        if (!(entity1 instanceof Mob) || !(entity2 instanceof Mob)) {
            return false;
        }

        Mob mob1 = (Mob) entity1;
        Mob mob2 = (Mob) entity2;

        // Non stackare se uno dei due ha un target (sta combattendo)
        if (mob1.getTarget() != null || mob2.getTarget() != null) {
            return false;
        }

        // Permetti il stacking anche se hanno nomi personalizzati (dal nostro sistema di stack)
        // Ma non stackare se hanno altri nomi personalizzati
        boolean mob1HasStackName = mob1.getCustomName() != null &&
                mob1.getCustomName().contains("x ") && mob1.getCustomName().startsWith("§e");
        boolean mob2HasStackName = mob2.getCustomName() != null &&
                mob2.getCustomName().contains("x ") && mob2.getCustomName().startsWith("§e");

        // Se uno ha un nome personalizzato che non è del nostro sistema, non stackare
        if ((mob1.getCustomName() != null && !mob1HasStackName) ||
                (mob2.getCustomName() != null && !mob2HasStackName)) {
            return false;
        }

        // Controlla che non siano troppo distanti verticalmente
        if (Math.abs(entity1.getLocation().getY() - entity2.getLocation().getY()) > 3) {
            return false;
        }

        return true;
    }

    private boolean canStackItemsTogether(Item item1, Item item2) {
        ItemStack stack1 = item1.getItemStack();
        ItemStack stack2 = item2.getItemStack();

        // Devono essere dello stesso tipo e avere gli stessi metadati
        if (!stack1.isSimilar(stack2)) {
            return false;
        }

        // Controlla che non siano troppo distanti verticalmente
        if (Math.abs(item1.getLocation().getY() - item2.getLocation().getY()) > 2) {
            return false;
        }

        // Non stackare se hanno nomi personalizzati che non sono del nostro sistema
        boolean item1HasStackName = item1.getCustomName() != null &&
                item1.getCustomName().contains("x ") && item1.getCustomName().startsWith("§b");
        boolean item2HasStackName = item2.getCustomName() != null &&
                item2.getCustomName().contains("x ") && item2.getCustomName().startsWith("§b");

        if ((item1.getCustomName() != null && !item1HasStackName) ||
                (item2.getCustomName() != null && !item2HasStackName)) {
            return false;
        }

        return true;
    }

    private boolean isStackableType(Entity entity) {
        return stackableTypes.contains(entity.getType().name());
    }

    private boolean isPlayerNearby(Location location) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(location.getWorld()) &&
                    player.getLocation().distance(location) <= playerRadius) {
                return true;
            }
        }
        return false;
    }

    private void updateDisplayName(Entity entity, int stackSize) {
        if (stackSize > 1) {
            entity.setCustomName("§e" + stackSize + "x §f" + formatEntityName(entity.getType().name()));
            entity.setCustomNameVisible(true);
        } else {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        }
    }

    private void updateItemDisplayName(Item item, int stackSize) {
        if (stackSize > item.getItemStack().getAmount()) {
            String itemName = item.getItemStack().getType().name().toLowerCase().replace("_", " ");
            item.setCustomName("§b" + stackSize + "x §f" + itemName);
            item.setCustomNameVisible(true);
        } else {
            item.setCustomName(null);
            item.setCustomNameVisible(false);
        }
    }

    private String formatEntityName(String entityType) {
        return entityType.toLowerCase().replace("_", " ");
    }
}