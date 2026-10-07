package net.tfminecraft.companionpets.item;

import org.bukkit.Material;

/**
 * A stack's material and custom provider identity, from {@link ItemRef#identify}. Provider lookups are
 * reflective and read item data, so identify a stack once and compare the result with every configured item.
 */
public record ItemIdentity(Material material, String mmoType, String mmoId, String itemsAdderId, boolean known) { }
