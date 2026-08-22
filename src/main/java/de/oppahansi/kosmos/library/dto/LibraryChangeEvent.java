package de.oppahansi.kosmos.library.dto;

import java.util.UUID;

/**
 * One title's worth of "something changed" — deliberately a pointer, not a payload. The frontend
 * already has a REST endpoint to fetch one movie/show/anime/episode by id, so duplicating that
 * shape into the event body would mean maintaining it twice. {@code contentType} reuses {@code
 * MediaItem.contentType}'s existing vocabulary ({@code movie}/{@code show}/{@code anime}/{@code
 * episode}/{@code anime_episode}) — no new taxonomy. See {@link
 * de.oppahansi.kosmos.library.LibraryChangeBroadcaster}'s own doc for why this exists as a second,
 * deliberately parallel channel alongside {@code NotificationEvent} rather than reusing it.
 */
public record LibraryChangeEvent(String contentType, UUID mediaItemId) {}
