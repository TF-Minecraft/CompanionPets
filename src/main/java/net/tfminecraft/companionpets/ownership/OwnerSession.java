package net.tfminecraft.companionpets.ownership;

import java.util.UUID;

/** Immutable authority token; safe to read from an async chat callback. */
public record OwnerSession(UUID account, UUID character, long token) { }
