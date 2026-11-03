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
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Trip;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class DepartureStopsTest {
    private static final long MINUTE = 60 * 1000;
    private static final long MAX_DIFF = 30 * MINUTE;

    private static final Line LINE_S1 = new Line(null, null, Product.SUBURBAN_TRAIN, "S1");
    private static final Line LINE_S2 = new Line(null, null, Product.SUBURBAN_TRAIN, "S2");

    private static final Location STATION_A = new Location(LocationType.STATION, "a", null, "Station A");
    private static final Location STATION_B = new Location(LocationType.STATION, "b", null, "Station B");
    private static final Location STATION_C = new Location(LocationType.STATION, "c", null, "Station C");

    @Test
    public void stopsOfLegIncludesDepartureIntermediateAndArrival() {
        final Trip.Public leg = publicLeg(LINE_S1, new Date(1000 * MINUTE), Arrays.asList(STATION_B));

        final List<Stop> stops = DepartureStops.stopsOf(leg);

        Assert.assertEquals(3, stops.size());
        Assert.assertEquals(STATION_A, stops.get(0).location);
        Assert.assertEquals(STATION_B, stops.get(1).location);
        Assert.assertEquals(STATION_C, stops.get(2).location);
    }

    @Test
    public void stopsOfLegWithoutIntermediateStops() {
        final Trip.Public leg = publicLeg(LINE_S1, new Date(1000 * MINUTE), null);

        final List<Stop> stops = DepartureStops.stopsOf(leg);

        Assert.assertEquals(2, stops.size());
        Assert.assertEquals(STATION_A, stops.get(0).location);
        Assert.assertEquals(STATION_C, stops.get(1).location);
    }

    @Test
    public void findLegPicksMatchingLine() {
        final Date departureTime = new Date(1000 * MINUTE);
        final Trip.Public matchingLeg = publicLeg(LINE_S2, departureTime, null);
        final List<Trip> trips = Arrays.asList(trip(publicLeg(LINE_S1, departureTime, null)),
                trip(matchingLeg));

        final Trip.Public leg = DepartureStops.findLeg(trips, LINE_S2, departureTime.getTime(), MAX_DIFF);

        Assert.assertSame(matchingLeg, leg);
    }

    @Test
    public void findLegPicksClosestDepartureTime() {
        final long requestedTime = 1000 * MINUTE;
        final Trip.Public wrongTimeLeg = publicLeg(LINE_S1, new Date(requestedTime - 20 * MINUTE), null);
        final Trip.Public closestLeg = publicLeg(LINE_S1, new Date(requestedTime + 2 * MINUTE), null);
        final List<Trip> trips = Arrays.asList(trip(wrongTimeLeg), trip(closestLeg));

        final Trip.Public leg = DepartureStops.findLeg(trips, LINE_S1, requestedTime, MAX_DIFF);

        Assert.assertSame(closestLeg, leg);
    }

    @Test
    public void findLegIgnoresTripsWithOtherLines() {
        final long requestedTime = 1000 * MINUTE;
        final List<Trip> trips = Arrays.asList(trip(publicLeg(LINE_S1, new Date(requestedTime), null)));

        final Trip.Public leg = DepartureStops.findLeg(trips, LINE_S2, requestedTime, MAX_DIFF);

        Assert.assertNull(leg);
    }

    @Test
    public void findLegIgnoresTooDistantDepartureTimes() {
        final long requestedTime = 1000 * MINUTE;
        final List<Trip> trips = Arrays
                .asList(trip(publicLeg(LINE_S1, new Date(requestedTime + 31 * MINUTE), null)));

        final Trip.Public leg = DepartureStops.findLeg(trips, LINE_S1, requestedTime, MAX_DIFF);

        Assert.assertNull(leg);
    }

    @Test
    public void findLegFallsBackToLabelAndProduct() {
        final long requestedTime = 1000 * MINUTE;
        // same label and product, but a different network
        final Line otherNetwork = new Line(null, "some-network", Product.SUBURBAN_TRAIN, "S1");
        final Trip.Public leg = publicLeg(otherNetwork, new Date(requestedTime), null);
        final List<Trip> trips = Arrays.asList(trip(leg));

        final Trip.Public foundLeg = DepartureStops.findLeg(trips, new Line(null, "other-network",
                Product.SUBURBAN_TRAIN, "S1"), requestedTime, MAX_DIFF);

        Assert.assertSame(leg, foundLeg);
    }

    @Test
    public void findLegDoesNotMatchOtherProduct() {
        final long requestedTime = 1000 * MINUTE;
        final Line bus = new Line(null, "some-network", Product.BUS, "S1");
        final List<Trip> trips = Arrays.asList(trip(publicLeg(bus, new Date(requestedTime), null)));

        final Trip.Public leg = DepartureStops.findLeg(trips, new Line(null, "other-network",
                Product.SUBURBAN_TRAIN, "S1"), requestedTime, MAX_DIFF);

        Assert.assertNull(leg);
    }

    @Test
    public void findLegHandlesNoTrips() {
        Assert.assertNull(DepartureStops.findLeg(null, LINE_S1, 1000 * MINUTE, MAX_DIFF));
        Assert.assertNull(DepartureStops.findLeg(new ArrayList<Trip>(), LINE_S1, 1000 * MINUTE, MAX_DIFF));
    }

    private static Trip trip(final Trip.Public publicLeg) {
        final List<Trip.Leg> legs = new ArrayList<>();
        legs.add(publicLeg);
        return new Trip(null, STATION_A, STATION_C, legs, null, null, null);
    }

    private static Trip.Public publicLeg(final Line line, final Date departureTime,
            final List<Location> intermediateStopLocations) {
        final Stop departureStop = new Stop(STATION_A, true, departureTime, null, null, null);
        final Stop arrivalStop = new Stop(STATION_C, false, new Date(departureTime.getTime() + 20 * MINUTE), null,
                null, null);

        List<Stop> intermediateStops = null;
        if (intermediateStopLocations != null) {
            intermediateStops = new ArrayList<>();
            long time = departureTime.getTime();
            for (final Location location : intermediateStopLocations) {
                time += 5 * MINUTE;
                intermediateStops.add(new Stop(location, false, new Date(time), null, null, null));
            }
        }

        return new Trip.Public(line, STATION_C, departureStop, arrivalStop, intermediateStops, null, null);
    }
}
