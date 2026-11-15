package de.teamglow;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;

public class TeamGlow implements ModInitializer {

    private static final String PREFIX = "tg_";
    private static final long INVITE_MS = 60_000;

    private record Invite(UUID from, long expires) {}

    private static final Map<UUID, Invite> invites = new HashMap<>();   // Ziel -> Einladung
    private static final Map<UUID, Integer> teamOf = new HashMap<>();   // Spieler -> Team-ID
    private static final Map<Integer, Set<UUID>> teams = new HashMap<>();
    private static int nextId = 1;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> cleanup(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            invites.remove(handler.player.getUUID());
            leave(server, handler.player);
        });
    }

    private void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("party")
            .then(Commands.literal("invite")
                .then(Commands.argument("spieler", EntityArgument.player())
                    .executes(c -> invite(c.getSource().getServer(),
                        c.getSource().getPlayerOrException(),
                        EntityArgument.getPlayer(c, "spieler")))))
            .then(Commands.literal("accept")
                .executes(c -> accept(c.getSource().getServer(), c.getSource().getPlayerOrException())))
            .then(Commands.literal("deny")
                .executes(c -> deny(c.getSource().getServer(), c.getSource().getPlayerOrException())))
            .then(Commands.literal("leave")
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!teamOf.containsKey(p.getUUID())) { msg(p, "Du bist in keinem Team.", ChatFormatting.RED); return 0; }
                    leave(c.getSource().getServer(), p);
                    msg(p, "Du hast das Team verlassen.", ChatFormatting.YELLOW);
                    return 1;
                }))
            .then(Commands.literal("list")
                .executes(c -> list(c.getSource().getServer(), c.getSource().getPlayerOrException()))));
    }

    // ---------- Befehle ----------

    private int invite(MinecraftServer server, ServerPlayer from, ServerPlayer to) {
        if (from == to) { msg(from, "Du kannst dich nicht selbst einladen.", ChatFormatting.RED); return 0; }
        if (teamOf.containsKey(to.getUUID())) { msg(from, to.getGameProfile().name() + " ist schon in einem Team.", ChatFormatting.RED); return 0; }

        invites.put(to.getUUID(), new Invite(from.getUUID(), System.currentTimeMillis() + INVITE_MS));
        msg(from, "Team-Anfrage an " + to.getGameProfile().name() + " gesendet.", ChatFormatting.GREEN);

        Component accept = Component.literal("[Annehmen]").withStyle(Style.EMPTY
            .withColor(ChatFormatting.GREEN).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/party accept"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Team beitreten"))));
        Component deny = Component.literal("[Ablehnen]").withStyle(Style.EMPTY
            .withColor(ChatFormatting.RED).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/party deny"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Anfrage ablehnen"))));

        to.sendSystemMessage(Component.literal(from.getGameProfile().name() + " möchte dich in sein Team einladen! ")
            .withStyle(ChatFormatting.YELLOW)
            .append(accept).append(Component.literal(" ")).append(deny));
        return 1;
    }

    private int accept(MinecraftServer server, ServerPlayer p) {
        Invite inv = invites.remove(p.getUUID());
        if (inv == null || inv.expires < System.currentTimeMillis()) {
            msg(p, "Keine (gültige) Team-Anfrage vorhanden.", ChatFormatting.RED);
            return 0;
        }
        ServerPlayer inviter = server.getPlayerList().getPlayer(inv.from);
        if (inviter == null) { msg(p, "Der Einladende ist nicht mehr online.", ChatFormatting.RED); return 0; }
        if (teamOf.containsKey(p.getUUID())) { msg(p, "Du bist schon in einem Team.", ChatFormatting.RED); return 0; }

        int id = teamOf.getOrDefault(inviter.getUUID(), -1);
        if (id == -1) {
            id = nextId++;
            addToTeam(server, inviter, id);
        }
        addToTeam(server, p, id);

        for (UUID u : teams.get(id)) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null) msg(m, p.getGameProfile().name() + " ist dem Team beigetreten!", ChatFormatting.GREEN);
        }
        return 1;
    }

    private int deny(MinecraftServer server, ServerPlayer p) {
        Invite inv = invites.remove(p.getUUID());
        if (inv == null) { msg(p, "Keine Team-Anfrage vorhanden.", ChatFormatting.RED); return 0; }
        msg(p, "Anfrage abgelehnt.", ChatFormatting.YELLOW);
        ServerPlayer inviter = server.getPlayerList().getPlayer(inv.from);
        if (inviter != null) msg(inviter, p.getGameProfile().name() + " hat deine Anfrage abgelehnt.", ChatFormatting.RED);
        return 1;
    }

    private int list(MinecraftServer server, ServerPlayer p) {
        Integer id = teamOf.get(p.getUUID());
        if (id == null) { msg(p, "Du bist in keinem Team.", ChatFormatting.RED); return 0; }
        StringBuilder sb = new StringBuilder("Team: ");
        for (UUID u : teams.get(id)) {
            ServerPlayer m = server.getPlayerList().getPlayer(u);
            if (m != null) sb.append(m.getGameProfile().name()).append(" ");
        }
        msg(p, sb.toString().trim(), ChatFormatting.GREEN);
        return 1;
    }

    // ---------- Team-Logik ----------

    private static void addToTeam(MinecraftServer server, ServerPlayer p, int id) {
        Scoreboard sb = server.getScoreboard();
        String name = PREFIX + id;
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            team.setColor(ChatFormatting.GREEN); // grüne Glow-Farbe
        }
        sb.addPlayerToTeam(p.getScoreboardName(), team);
        teams.computeIfAbsent(id, k -> new HashSet<>()).add(p.getUUID());
        teamOf.put(p.getUUID(), id);
        p.setGlowingTag(true);
    }

    private static void leave(MinecraftServer server, ServerPlayer p) {
        Integer id = teamOf.remove(p.getUUID());
        if (id == null) return;

        Scoreboard sb = server.getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(PREFIX + id);
        if (team != null) sb.removePlayerFromTeam(p.getScoreboardName(), team);
        p.setGlowingTag(false);

        Set<UUID> members = teams.get(id);
        if (members == null) return;
        members.remove(p.getUUID());

        // Team mit nur noch einer Person auflösen
        if (members.size() < 2) {
            for (UUID u : new ArrayList<>(members)) {
                teamOf.remove(u);
                ServerPlayer m = server.getPlayerList().getPlayer(u);
                if (m != null) {
                    if (team != null) sb.removePlayerFromTeam(m.getScoreboardName(), team);
                    m.setGlowingTag(false);
                    msg(m, "Das Team wurde aufgelöst.", ChatFormatting.YELLOW);
                }
            }
            teams.remove(id);
            if (team != null) sb.removePlayerTeam(team);
        }
    }

    /** Beim Join: Reste von früheren Sessions (Server-Neustart) entfernen. */
    private static void cleanup(MinecraftServer server, ServerPlayer p) {
        Scoreboard sb = server.getScoreboard();
        PlayerTeam t = sb.getPlayersTeam(p.getScoreboardName());
        if (t != null && t.getName().startsWith(PREFIX) && !teamOf.containsKey(p.getUUID())) {
            sb.removePlayerFromTeam(p.getScoreboardName(), t);
            if (t.getPlayers().isEmpty()) sb.removePlayerTeam(t);
            p.setGlowingTag(false);
        }
    }

    private static void msg(ServerPlayer p, String text, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(text).withStyle(color));
    }
}
