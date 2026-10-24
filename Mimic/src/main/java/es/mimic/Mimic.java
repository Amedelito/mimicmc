package es.mimic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.stream.Collectors;

public final class Mimic extends JavaPlugin implements Listener, TabExecutor {

    private static final String TAG = "mimic_display";
    private NamespacedKey pawKey, flagKey;
    private final Map<UUID, Active> act = new HashMap<>();

    private static final class Active {
        Player p; String id; ConfigurationSection c; ItemDisplay d;
        ItemStack oldOff; Location last; BukkitTask task; int t; double phase;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        pawKey = new NamespacedKey(this, "paw");
        flagKey = new NamespacedKey(this, "disfrazado");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("transformar");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        // limpia displays huerfanos de un cierre brusco
        for (var w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class))
                if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    @Override public void onDisable() {
        for (Active a : new ArrayList<>(act.values())) revert(a.p, true);
    }

    // ---------------------------------------------------------------- logica

    private boolean transform(Player p, String id) {
        ConfigurationSection c = getConfig().getConfigurationSection("criaturas." + id);
        if (c == null) return false;
        if (act.containsKey(p.getUniqueId())) revert(p, true);

        Active a = new Active();
        a.p = p; a.id = id; a.c = c;

        Material mat = Material.matchMaterial(c.getString("material", "PAPER"));
        if (mat == null) mat = Material.PAPER;
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setCustomModelData(c.getInt("modelo"));
        item.setItemMeta(m);

        a.d = p.getWorld().spawn(p.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(2);
            d.addScoreboardTag(TAG);
        });
        if (getConfig().getBoolean("ocultar-a-si-mismo", true)) p.hideEntity(this, a.d);

        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        setAttr(p, Attribute.GENERIC_SCALE, c.getDouble("escala-jugador", 1.0));
        setAttr(p, Attribute.GENERIC_MOVEMENT_SPEED, 0.1 * c.getDouble("velocidad", 1.0));
        for (String s : c.getStringList("efectos")) {
            String[] sp = s.split(":");
            PotionEffectType t = effect(sp[0]);
            if (t == null) continue;
            int amp = sp.length > 1 ? Integer.parseInt(sp[1]) : 0;
            p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, false, false, false));
        }

        int paw = c.getInt("modelo-mano", 0);
        if (paw > 0) {
            a.oldOff = p.getInventory().getItemInOffHand().clone();
            ItemStack pi = new ItemStack(mat);
            ItemMeta pm = pi.getItemMeta();
            pm.setCustomModelData(paw);
            pm.setDisplayName(" ");
            pm.getPersistentDataContainer().set(pawKey, PersistentDataType.BYTE, (byte) 1);
            pi.setItemMeta(pm);
            p.getInventory().setItemInOffHand(pi);
        }

        p.getPersistentDataContainer().set(flagKey, PersistentDataType.BYTE, (byte) 1);
        act.put(p.getUniqueId(), a);
        a.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(a), 0L, 1L);
        return true;
    }

    private void revert(Player p, boolean restoreOff) {
        Active a = act.remove(p.getUniqueId());
        if (a != null) {
            if (a.task != null) a.task.cancel();
            if (a.d != null) a.d.remove();
            if (restoreOff && a.oldOff != null) p.getInventory().setItemInOffHand(a.oldOff);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            for (String s : a.c.getStringList("efectos")) {
                PotionEffectType t = effect(s.split(":")[0]);
                if (t != null) p.removePotionEffect(t);
            }
        }
        setAttr(p, Attribute.GENERIC_SCALE, 1.0);
        setAttr(p, Attribute.GENERIC_MOVEMENT_SPEED, 0.1);
        p.getPersistentDataContainer().remove(flagKey);
    }

    private void tick(Active a) {
        Player p = a.p;
        if (!p.isOnline() || p.isDead()) { revert(p, true); return; }

        Location l = p.getLocation();
        l.setPitch(0);
        a.d.teleport(l);
        if (++a.t % 2 != 0) return;

        boolean mov = a.last != null && a.last.getWorld() == l.getWorld()
                && Math.hypot(l.getX() - a.last.getX(), l.getZ() - a.last.getZ()) > 0.02;
        a.last = l;
        a.phase += mov ? 0.9 : 0.18;

        float k = (float) a.c.getDouble("escala", 1.0);
        double bob = Math.sin(a.phase) * (mov ? 0.06 : 0.015) * k;
        float roll = mov ? (float) (Math.sin(a.phase * 0.5) * 0.12) : 0f;
        float sy = p.isSneaking() ? 0.85f : 1f;

        Quaternionf q = new Quaternionf()
                .rotateY((float) Math.toRadians(a.c.getDouble("giro", 0)))
                .rotateZ(roll);
        if (p.isSwimming() || p.isGliding()) q.rotateX((float) Math.toRadians(80));

        float off = (float) (a.c.getDouble("altura", 0.4) + bob);
        a.d.setInterpolationDelay(0);
        a.d.setTransformation(new Transformation(new Vector3f(0, off, 0), q, new Vector3f(k, k * sy, k), new Quaternionf()));
    }

    private void setAttr(Player p, Attribute at, double v) {
        AttributeInstance ai = p.getAttribute(at);
        if (ai != null) ai.setBaseValue(v);
    }

    private PotionEffectType effect(String n) {
        return Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(n.toLowerCase(Locale.ROOT)));
    }

    private boolean isPaw(ItemStack i) {
        return i != null && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(pawKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler public void onQuit(PlayerQuitEvent e) { revert(e.getPlayer(), true); }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!act.containsKey(p.getUniqueId())
                && p.getPersistentDataContainer().has(flagKey, PersistentDataType.BYTE)) {
            revert(p, false); // limpieza tras cierre brusco
            p.getInventory().setItemInOffHand(null);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Active a = act.get(p.getUniqueId());
        if (a == null) return;
        e.getDrops().removeIf(this::isPaw);
        if (a.oldOff != null && !a.oldOff.getType().isAir()) e.getDrops().add(a.oldOff);
        revert(p, false);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (isPaw(e.getCurrentItem()) || isPaw(e.getCursor())) { e.setCancelled(true); return; }
        if (e.getWhoClicked() instanceof Player p && act.containsKey(p.getUniqueId())
                && e.getClickedInventory() == p.getInventory() && e.getSlot() == 40) e.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (isPaw(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        if (act.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- comandos

    private List<Player> targets(CommandSender s, String arg) {
        List<Player> r = new ArrayList<>();
        String a = arg.toLowerCase(Locale.ROOT);
        if (a.equals("yo") || a.equals("me")) { if (s instanceof Player p) r.add(p); }
        else if (a.equals("all") || a.equals("todos")) r.addAll(Bukkit.getOnlinePlayers());
        else if (a.startsWith("@")) {
            try { for (Entity e : Bukkit.selectEntities(s, arg)) if (e instanceof Player p) r.add(p); }
            catch (IllegalArgumentException ex) { /* selector invalido */ }
        } else { Player p = Bukkit.getPlayerExact(arg); if (p != null) r.add(p); }
        return r;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("mimic.use")) { s.sendMessage("§cSolo OPs."); return true; }
        if (a.length == 0) {
            s.sendMessage("§e/transformar <yo|all|jugador|@selector> <criatura>");
            s.sendMessage("§e/transformar quitar <yo|all|jugador>  §7| §e/transformar lista §7| §e/transformar recargar");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("lista")) {
            s.sendMessage("§aCriaturas: §f" + criaturas().stream().collect(Collectors.joining(", ")));
            return true;
        }
        if (sub.equals("recargar")) { reloadConfig(); s.sendMessage("§aConfig recargada."); return true; }
        if (sub.equals("quitar") || sub.equals("normal")) {
            List<Player> t = targets(s, a.length > 1 ? a[1] : "yo");
            if (t.isEmpty()) { s.sendMessage("§cObjetivo no encontrado."); return true; }
            t.forEach(p -> revert(p, true));
            s.sendMessage("§aVuelven a la normalidad: §f" + t.size());
            return true;
        }
        if (a.length < 2) { s.sendMessage("§cFalta la criatura. Usa /transformar lista"); return true; }
        String id = a[1].toLowerCase(Locale.ROOT);
        if (!criaturas().contains(id)) { s.sendMessage("§cCriatura desconocida. Usa /transformar lista"); return true; }
        List<Player> t = targets(s, a[0]);
        if (t.isEmpty()) { s.sendMessage("§cObjetivo no encontrado."); return true; }
        t.forEach(p -> transform(p, id));
        s.sendMessage("§aTransformados en §f" + id + "§a: §f" + t.size());
        return true;
    }

    private Set<String> criaturas() {
        ConfigurationSection cs = getConfig().getConfigurationSection("criaturas");
        return cs == null ? Set.of() : cs.getKeys(false);
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("mimic.use")) return List.of();
        List<String> out = new ArrayList<>();
        List<String> who = new ArrayList<>(List.of("yo", "all"));
        Bukkit.getOnlinePlayers().forEach(p -> who.add(p.getName()));
        if (a.length == 1) { out.addAll(who); out.addAll(List.of("quitar", "lista", "recargar")); }
        else if (a.length == 2) {
            if (a[0].equalsIgnoreCase("quitar")) out.addAll(who);
            else out.addAll(criaturas());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
