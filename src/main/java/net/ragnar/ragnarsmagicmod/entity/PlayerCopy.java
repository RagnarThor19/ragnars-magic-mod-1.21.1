package net.ragnar.ragnarsmagicmod.entity;

import java.util.Optional;
import java.util.UUID;

/** A mob drawn as a copy of a player (their skin, arm width and gear) - see PlayerCopyRenderer. */
public interface PlayerCopy {
    Optional<UUID> getOwnerUuid();
}
