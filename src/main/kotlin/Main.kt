package com.m1lkin.quicklysell

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask


class QuickSellPlugin : JavaPlugin(), Listener {

    private val sellMenus = mutableMapOf<Player, Inventory>()
    private lateinit var priceConfig: FileConfiguration
    private lateinit var economy: Economy
    private val priceUpdateTasks = mutableMapOf<Player, BukkitTask>()

    override fun onEnable() {
        if (!setupEconomy()) {
            logger.severe("Vault не найден! Отключение плагина.")
            server.pluginManager.disablePlugin(this)
            return
        }

        server.pluginManager.registerEvents(this, this)
        loadPriceConfig()
    }

    private fun setupEconomy(): Boolean {
        if (server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val rsp: RegisteredServiceProvider<Economy> = server.servicesManager.getRegistration(Economy::class.java)
            ?: return false
        economy = rsp.provider
        return true
    }

    private fun loadPriceConfig() {
        saveDefaultConfig()
        priceConfig = config
    }

    private fun openSellMenu(player: Player) {
        val inventory = Bukkit.createInventory(null, 54, "Продажа предметов")

        inventory.setItem(53, createButton(Material.EMERALD_BLOCK, "§a§lПродать все"))
        inventory.setItem(45, createButton(Material.BOOK, "§e§lИнформация о продаже",
            "§7Положите предметы в", "§7инвентарь для продажи", "§7Нажмите 'Продать все'", "§7чтобы продать предметы"))
        updatePriceButton(inventory, player)
        for (i in 47 until 53) {
            inventory.setItem(i, createButton(Material.STAINED_GLASS_PANE, " "))
        }

        player.openInventory(inventory)
        sellMenus[player] = inventory
    }

    private fun createButton(material: Material, name: String, vararg lore: String): ItemStack {
        val button = ItemStack(material)
        val meta = button.itemMeta
        meta?.displayName = name
        meta?.lore = lore.toList()
        button.itemMeta = meta
        return button
    }

    private fun updatePriceButton(inventory: Inventory, player: Player) {
        val totalValue = calculateTotalValue(inventory, player)
        inventory.setItem(46, createButton(Material.GOLD_NUGGET, "§6§lТекущая стоимость",
            "§7Стоимость предметов:", "§e$totalValue"))
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val clickedInventory = event.clickedInventory ?: return

        if (clickedInventory == sellMenus[player]) {
            when (event.slot) {
                53 -> {
                    event.isCancelled = true
                    sellItems(player)
                }
                in 45..52 -> {
                    event.isCancelled = true
                }
                else -> schedulePriceUpdate(player, clickedInventory)
            }
        } else if (event.view.topInventory == sellMenus[player]) {
            schedulePriceUpdate(player, event.view.topInventory)
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.inventory == sellMenus[player]) {
            schedulePriceUpdate(player, event.inventory)
        }
    }

    private fun schedulePriceUpdate(player: Player, inventory: Inventory) {
        priceUpdateTasks[player]?.cancel()
        priceUpdateTasks[player] = object : BukkitRunnable() {
            override fun run() {
                updatePriceButton(inventory, player)
                priceUpdateTasks.remove(player)
            }
        }.runTaskLater(this, 5)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as Player
        val inventory = sellMenus.remove(player) ?: return
        returnItems(player, inventory)
        priceUpdateTasks[player]?.cancel()
        priceUpdateTasks.remove(player)
    }

    private fun sellItems(player: Player) {
        val inventory = sellMenus[player] ?: return
        val totalValue = calculateTotalValue(inventory, player)

        for (i in 0 until 45) {
            inventory.clear(i)
        }

        economy.depositPlayer(player, totalValue)
        player.sendMessage("§aВы продали предметы на сумму: $totalValue")
        updatePriceButton(inventory, player)
        player.closeInventory()
    }

    private fun calculateTotalValue(inventory: Inventory, player: Player): Double {
        var totalValue = 0.0
        for (i in 0 until 45) {
            val item = inventory.getItem(i) ?: continue
            val amount = calculateItemValue(item)
            if (amount == 0.0) {
                inventory.setItem(i, null)
                val leftover = player.inventory.addItem(item)
                if (leftover.isNotEmpty()) {
                    player.world.dropItem(player.location, leftover[0]!!)
                }
            }
            totalValue += calculateItemValue(item)
        }
        return totalValue
    }

    private fun calculateItemValue(item: ItemStack): Double {
        val unitPrice: Double

        if (item.itemMeta.hasDisplayName()) {
            val itemName = item.itemMeta?.displayName
            unitPrice = priceConfig.getDouble("prices.named.$itemName", 0.0)
        } else {
            unitPrice = priceConfig.getDouble("prices.type.${item.type.name.lowercase()}", 0.0)
        }

        return unitPrice * item.amount
    }

    private fun returnItems(player: Player, inventory: Inventory) {
        for (i in 0 until 45) {
            val item = inventory.getItem(i) ?: continue
            val leftover = player.inventory.addItem(item)
            if (leftover.isNotEmpty()) {
                player.world.dropItem(player.location, leftover[0]!!)
            }
        }
        player.updateInventory()
    }

    override fun onCommand(sender: org.bukkit.command.CommandSender, command: org.bukkit.command.Command, label: String, args: Array<out String>): Boolean {
        when {
            command.name.equals("quicksell", ignoreCase = true) && sender is Player -> {
                openSellMenu(sender)
                return true
            }
            command.name.equals("quickselladd", ignoreCase = true) && sender.hasPermission("quicksell.add") -> {
                if (args.isEmpty() || args.size > 1 ) {
                    sender.sendMessage("Неправильное кол-во аргументов")
                    return false
                }
                val value = args[0].toDoubleOrNull()
                if (value == null) {
                    sender.sendMessage("Введите правильную стоимость")
                    return false
                }
                addItem(sender as Player, value)
                return true
            }
            command.name.equals("quicksellreload", ignoreCase = true) && sender.hasPermission("quicksell.reload") -> {
                reloadPriceConfig()
                sender.sendMessage("§aЦены на продажу предметов перезагружены.")
                return true
            }
        }
        return false
    }

    private fun addItem(player: Player, value: Double) {
        val item = player.inventory.itemInMainHand

        if (item != null && Material.AIR != item.type) {
            if (item.itemMeta.hasDisplayName()) {
                priceConfig.set("prices.named.${item.itemMeta.displayName}", value)
            } else {
                priceConfig.set("prices.type.${item.type.name.lowercase()}", value)
            }
            saveConfig()
            player.sendMessage("§aПредмет добавлен на продажу!")
        } else {
            player.sendMessage("В руке ничего нет.")
        }
    }

    private fun reloadPriceConfig() {
        reloadConfig()
        priceConfig = config
    }
}