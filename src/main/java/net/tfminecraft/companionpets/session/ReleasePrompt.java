package net.tfminecraft.companionpets.session;

import java.util.UUID;

public record ReleasePrompt(UUID petId, long expiresAt) {
}
