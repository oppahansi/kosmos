package de.oppahansi.kosmos.media.dto;

import java.util.UUID;

/** Reassigns which registered root folder a title belongs to. Does not move files on disk. */
public record UpdateRootFolderRequest(UUID rootFolderId) {}
