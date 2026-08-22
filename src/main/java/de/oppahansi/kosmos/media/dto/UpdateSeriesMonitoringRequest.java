package de.oppahansi.kosmos.media.dto;

/**
 * @param mode ALL, NONE, FUTURE, or MISSING — see {@code ShowService#updateMonitoring}'s own doc.
 * @param seasonNumber scopes the change to one season instead of the whole show/anime, when given.
 */
public record UpdateSeriesMonitoringRequest(String mode, Integer seasonNumber) {}
