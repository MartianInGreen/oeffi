/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.oeffi.stations;

import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Trip;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Pure logic to determine the stops of a single vehicle, i.e. the route of a specific departure.
 * <p>
 * The underlying transport network interface only knows trips between two locations, so the leg that matches
 * the line and the departure time has to be picked from the trips.
 */
public final class DepartureStops {
    private DepartureStops() {
    }

    /**
     * Find the leg that is served by the given line and that departs closest to the requested time.
     *
     * @param trips
     *            trips to search, may be {@code null}
     * @param line
     *            line of the departure
     * @param departureTimeMs
     *            planned departure time of the departure
     * @param maxDepartureTimeDiffMs
     *            maximum difference between requested and found departure time
     * @return the matching leg, or {@code null} if there is none
     */
    @Nullable
    public static Trip.Public findLeg(@Nullable final List<Trip> trips, final Line line, final long departureTimeMs,
            final long maxDepartureTimeDiffMs) {
        // first try to find an exact match of the line, then fall back to matching just label and product, as
        // some networks do not fill in all attributes of a line consistently
        final Trip.Public leg = findLeg(trips, line, departureTimeMs, maxDepartureTimeDiffMs, true);
        if (leg != null)
            return leg;
        return findLeg(trips, line, departureTimeMs, maxDepartureTimeDiffMs, false);
    }

    @Nullable
    private static Trip.Public findLeg(@Nullable final List<Trip> trips, final Line line, final long departureTimeMs,
            final long maxDepartureTimeDiffMs, final boolean matchExactLine) {
        if (trips == null)
            return null;

        Trip.Public bestLeg = null;
        long bestDiff = Long.MAX_VALUE;

        for (final Trip trip : trips) {
            for (final Trip.Leg leg : trip.legs) {
                if (!(leg instanceof Trip.Public))
                    continue;

                final Trip.Public publicLeg = (Trip.Public) leg;
                if (matchExactLine ? !line.equals(publicLeg.line) : !sameLineLabel(line, publicLeg.line))
                    continue;

                final Date legDepartureTime = publicLeg.getDepartureTime(true);
                if (legDepartureTime == null)
                    continue;

                final long diff = Math.abs(legDepartureTime.getTime() - departureTimeMs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestLeg = publicLeg;
                }
            }
        }

        if (bestLeg != null && bestDiff <= maxDepartureTimeDiffMs)
            return bestLeg;
        else
            return null;
    }

    private static boolean sameLineLabel(final Line line1, final Line line2) {
        if (line1.label == null)
            return false;
        return line1.label.equals(line2.label) && line1.product == line2.product;
    }

    /**
     * Get all stops of the given leg, i.e. the departure stop, the intermediate stops and the arrival stop.
     */
    public static List<Stop> stopsOf(final Trip.Public leg) {
        final List<Stop> stops = new ArrayList<>();
        stops.add(leg.departureStop);
        final List<Stop> intermediateStops = leg.intermediateStops;
        if (intermediateStops != null)
            stops.addAll(intermediateStops);
        stops.add(leg.arrivalStop);
        return stops;
    }
}
