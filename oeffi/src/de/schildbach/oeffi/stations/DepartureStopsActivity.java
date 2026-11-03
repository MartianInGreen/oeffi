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

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ViewAnimator;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.schildbach.oeffi.Constants;
import de.schildbach.oeffi.MyActionBar;
import de.schildbach.oeffi.OeffiActivity;
import de.schildbach.oeffi.R;
import de.schildbach.oeffi.directions.PearlView;
import de.schildbach.oeffi.directions.QueryTripsRunnable;
import de.schildbach.oeffi.directions.TimeSpec;
import de.schildbach.oeffi.directions.TimeSpec.DepArr;
import de.schildbach.oeffi.network.NetworkProviderFactory;
import de.schildbach.oeffi.util.DividerItemDecoration;
import de.schildbach.oeffi.util.Formats;
import de.schildbach.pte.NetworkId;
import de.schildbach.pte.NetworkProvider;
import de.schildbach.pte.NetworkProvider.Accessibility;
import de.schildbach.pte.NetworkProvider.Capability;
import de.schildbach.pte.NetworkProvider.Optimize;
import de.schildbach.pte.NetworkProvider.WalkSpeed;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.Line;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.Position;
import de.schildbach.pte.dto.QueryTripsResult;
import de.schildbach.pte.dto.Stop;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Trip;
import de.schildbach.pte.dto.TripOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Shows the stops of a single vehicle (train, tram, bus, …), i.e. the route of a specific departure.
 * <p>
 * The stops are determined by querying trips from the departure station to the destination of the departure and
 * then picking the leg that matches the line and the departure time. If the transport network does not provide
 * any stop information, at least the origin and the destination are shown.
 */
public class DepartureStopsActivity extends OeffiActivity {
    private static final String INTENT_EXTRA_NETWORK = DepartureStopsActivity.class.getName() + ".network";
    private static final String INTENT_EXTRA_STATION = DepartureStopsActivity.class.getName() + ".station";
    private static final String INTENT_EXTRA_DEPARTURE = DepartureStopsActivity.class.getName() + ".departure";

    private static final String STATE_STOPS = DepartureStopsActivity.class.getName() + ".stops";
    private static final String STATE_STOPS_COMPLETE = DepartureStopsActivity.class.getName() + ".stops_complete";

    /**
     * Maximum difference between requested departure time and departure time of the matched leg. Kept small,
     * so that the stops of a neighbouring vehicle of the same line are not shown by accident.
     */
    private static final long MAX_DEPARTURE_TIME_DIFF_MS = 15 * DateUtils.MINUTE_IN_MILLIS;

    public static void start(final Context context, final NetworkId networkId, final Location station,
            final Departure departure) {
        final Intent intent = new Intent(context, DepartureStopsActivity.class);
        intent.putExtra(INTENT_EXTRA_NETWORK, requireNonNull(networkId));
        intent.putExtra(INTENT_EXTRA_STATION, requireNonNull(station));
        intent.putExtra(INTENT_EXTRA_DEPARTURE, requireNonNull(departure));
        context.startActivity(intent);
    }

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private QueryTripsRunnable queryTripsRunnable;
    @Nullable
    private ProgressDialog progressDialog;

    private NetworkId network;
    private Location station;
    private Departure departure;

    private MyActionBar actionBar;
    private LineView lineView;
    private TextView destinationView;
    private TextView timeView;
    private TextView noteView;
    private ViewAnimator viewAnimator;
    private RecyclerView listView;
    private StopsAdapter listAdapter;
    private View progressView;
    private TextView statusView;

    private final List<StopRow> rows = new ArrayList<>();

    private static final Logger log = LoggerFactory.getLogger(DepartureStopsActivity.class);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backgroundThread = new HandlerThread("queryTripsThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        final Intent intent = getIntent();
        network = (NetworkId) requireNonNull(intent.getSerializableExtra(INTENT_EXTRA_NETWORK));
        station = (Location) requireNonNull(intent.getSerializableExtra(INTENT_EXTRA_STATION));
        departure = (Departure) requireNonNull(intent.getSerializableExtra(INTENT_EXTRA_DEPARTURE));

        setContentView(R.layout.stations_departure_stops_content);
        final View contentView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, 0, insets.right, 0);
            return windowInsets;
        });

        actionBar = getMyActionBar();
        setPrimaryColor(R.color.bg_action_bar_stations);
        actionBar.setBack(v -> finish());
        actionBar.setPrimaryTitle(departure.line.label);
        actionBar.setSecondaryTitle(station.name);

        lineView = findViewById(R.id.stations_departure_stops_line);
        lineView.setLine(departure.line);

        destinationView = findViewById(R.id.stations_departure_stops_destination);
        final Location destination = departure.destination;
        if (destination != null) {
            destinationView.setText(Constants.DESTINATION_ARROW_PREFIX + destination.uniqueShortName());
            destinationView.setVisibility(View.VISIBLE);
            destinationView.setOnClickListener(destination.hasId() ? v -> {
                StationDetailsActivity.start(DepartureStopsActivity.this, network, destination);
                finish();
            } : null);
        } else {
            destinationView.setText(null);
        }

        final Date time = departure.getTime();
        timeView = findViewById(R.id.stations_departure_stops_time);
        timeView.setText(time != null ? Formats.formatTime(this, time.getTime()) : null);

        noteView = findViewById(R.id.stations_departure_stops_note);

        viewAnimator = findViewById(R.id.stations_departure_stops_layout);
        progressView = findViewById(R.id.stations_departure_stops_progress);
        statusView = findViewById(R.id.stations_departure_stops_status);

        listView = findViewById(R.id.stations_departure_stops_list);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL_LIST));
        listAdapter = new StopsAdapter(this);
        listView.setAdapter(listAdapter);
        ViewCompat.setOnApplyWindowInsetsListener(listView, (v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        final Serializable savedStops = savedInstanceState != null
                ? savedInstanceState.getSerializable(STATE_STOPS) : null;
        if (savedStops != null) {
            setStops((List<Stop>) savedStops, savedInstanceState.getBoolean(STATE_STOPS_COMPLETE, true));
        } else {
            loadStops();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        if (!rows.isEmpty()) {
            outState.putSerializable(STATE_STOPS, (Serializable) stops());
            outState.putBoolean(STATE_STOPS_COMPLETE, noteView.getVisibility() != View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (queryTripsRunnable != null)
            queryTripsRunnable.cancel();
        dismissProgressDialog();

        backgroundThread.getLooper().quit();

        super.onDestroy();
    }

    private void dismissProgressDialog() {
        final ProgressDialog progressDialog = this.progressDialog;
        this.progressDialog = null;
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (final IllegalArgumentException x) {
                // dialog was already dismissed
            }
        }
    }

    private List<Stop> stops() {
        final List<Stop> stops = new ArrayList<>(rows.size());
        for (final StopRow row : rows)
            stops.add(row.stop);
        return stops;
    }

    /**
     * Query the stops of the vehicle by looking for a trip between this station and the destination of the
     * departure, then pick the leg that is served by the same line at the same time.
     */
    private void loadStops() {
        final Location destination = departure.destination;
        final Date plannedTime = departure.plannedTime != null ? departure.plannedTime : departure.getTime();

        if (destination == null || !destination.hasId() || !station.hasId() || plannedTime == null) {
            setFallbackStops();
            return;
        }

        final NetworkProvider networkProvider = NetworkProviderFactory.provider(network);
        if (!networkProvider.hasCapabilities(Capability.TRIPS)) {
            setFallbackStops();
            return;
        }

        showLoading(R.string.stations_departure_stops_progress);

        progressDialog = ProgressDialog.show(this, null,
                getString(R.string.stations_departure_stops_progress), true, true, dialog -> {
                    if (queryTripsRunnable != null)
                        queryTripsRunnable.cancel();
                });
        progressDialog.setCanceledOnTouchOutside(false);

        final TimeSpec timeSpec = new TimeSpec.Absolute(DepArr.DEPART, plannedTime.getTime());
        final long requestedTimeMs = plannedTime.getTime();
        final TripOptions options = new TripOptions(networkProvider.defaultProducts(), Optimize.LEAST_DURATION,
                WalkSpeed.NORMAL, Accessibility.NEUTRAL, null);
        queryTripsRunnable = new QueryTripsRunnable(getResources(), progressDialog, handler, networkProvider, station,
                null, destination, timeSpec, options) {
            @Override
            protected void onPostExecute() {
                dismissProgressDialog();
            }

            @Override
            protected void onResult(final QueryTripsResult result) {
                log.debug("Got {}", result.toShortString());

                if (result.status == QueryTripsResult.Status.OK) {
                    final Trip.Public leg = DepartureStops.findLeg(result.trips, departure.line, requestedTimeMs,
                            MAX_DEPARTURE_TIME_DIFF_MS);
                    if (leg != null) {
                        setLegStops(leg);
                        return;
                    }
                }

                setFallbackStops();
            }
        };
        backgroundHandler.post(() -> {
            try {
                queryTripsRunnable.run();
            } catch (final RuntimeException x) {
                log.warn("Problem querying stops for " + departure, x);
                dismissProgressDialog();
                handler.post(this::setFallbackStops);
            }
        });
    }

    private void setLegStops(final Trip.Public leg) {
        final List<Stop> intermediateStops = leg.intermediateStops;

        setStops(DepartureStops.stopsOf(leg), intermediateStops != null && !intermediateStops.isEmpty());
    }

    /**
     * Fallback if the stops cannot be determined: show at least origin and destination.
     */
    private void setFallbackStops() {
        if (isFinishing() || isDestroyed())
            return;

        final List<Stop> stops = new ArrayList<>(2);
        final Date time = departure.getTime();
        final boolean isDeparture = departure.predictedTime == null;
        stops.add(new Stop(station, true, isDeparture ? time : departure.plannedTime, isDeparture ? null : time,
                departure.position, null));
        final Location destination = departure.destination;
        if (destination != null)
            stops.add(new Stop(destination, false, null, null, null, null));

        setStops(stops, false);
    }

    private void setStops(final List<Stop> stops, final boolean complete) {
        rows.clear();

        final Style style = departure.line.style;

        for (int i = 0; i < stops.size(); i++) {
            final Stop stop = stops.get(i);
            final PearlView.Type type;
            if (i == 0)
                type = PearlView.Type.DEPARTURE;
            else if (i == stops.size() - 1)
                type = PearlView.Type.ARRIVAL;
            else if (stop.plannedArrivalTime != null || stop.plannedDepartureTime != null)
                type = PearlView.Type.INTERMEDIATE;
            else
                type = PearlView.Type.PASSING;

            rows.add(new StopRow(stop, type, style));
        }

        // show a date for the first row and for every row that changes the day
        final long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            final Stop stop = rows.get(i).stop;
            final Date date = timeOf(stop, i == 0);
            if (date == null)
                continue;

            if (i == 0 || dateChanged(rows.get(i - 1).stop, stop))
                rows.get(i).date = Formats.formatDate(this, now, date.getTime(), true, "");
        }

        noteView.setVisibility(complete ? View.GONE : View.VISIBLE);
        noteView.setText(complete ? null : getString(R.string.stations_departure_stops_no_info));

        if (rows.isEmpty()) {
            showStatus(R.string.stations_departure_stops_no_info);
        } else {
            viewAnimator.setDisplayedChild(0);
            listAdapter.notifyDataSetChanged();
        }
    }

    private void showLoading(final int messageResId) {
        progressView.setVisibility(View.VISIBLE);
        statusView.setText(messageResId);
        viewAnimator.setDisplayedChild(1);
    }

    private void showStatus(final int messageResId) {
        progressView.setVisibility(View.GONE);
        statusView.setText(messageResId);
        viewAnimator.setDisplayedChild(1);
    }

    private static boolean dateChanged(final Stop previous, final Stop current) {
        final Date previousDate = timeOf(previous, false);
        final Date currentDate = timeOf(current, false);
        if (previousDate == null || currentDate == null)
            return false;

        final Calendar c = new GregorianCalendar();
        c.setTime(previousDate);
        final int previousDay = c.get(Calendar.DAY_OF_YEAR);
        final int previousYear = c.get(Calendar.YEAR);
        c.setTime(currentDate);
        return c.get(Calendar.DAY_OF_YEAR) != previousDay || c.get(Calendar.YEAR) != previousYear;
    }

    @Nullable
    private static Date timeOf(final Stop stop, final boolean preferDeparture) {
        if (preferDeparture) {
            if (stop.plannedDepartureTime != null)
                return stop.plannedDepartureTime;
            return stop.plannedArrivalTime;
        } else {
            if (stop.plannedArrivalTime != null)
                return stop.plannedArrivalTime;
            return stop.plannedDepartureTime;
        }
    }

    private static class StopRow {
        final Stop stop;
        final PearlView.Type type;
        @Nullable
        final Style style;
        @Nullable
        String date = null;

        StopRow(final Stop stop, final PearlView.Type type, @Nullable final Style style) {
            this.stop = stop;
            this.type = type;
            this.style = style;
        }
    }

    private class StopsAdapter extends RecyclerView.Adapter<StopViewHolder> {
        private final LayoutInflater inflater;

        public StopsAdapter(final Context context) {
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public StopViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
            return new StopViewHolder(inflater.inflate(R.layout.stations_departure_stops_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(final StopViewHolder holder, final int position) {
            final StopRow row = rows.get(position);
            holder.bind(row, network);
        }
    }

    static class StopViewHolder extends RecyclerView.ViewHolder {
        private final TextView dateView;
        private final TextView timeView;
        private final TextView delayView;
        private final TextView positionView;
        private final TextView nameView;
        private final PearlView pearlView;

        StopViewHolder(final View itemView) {
            super(itemView);

            dateView = itemView.findViewById(R.id.stations_departure_stops_entry_date);
            timeView = itemView.findViewById(R.id.stations_departure_stops_entry_time);
            delayView = itemView.findViewById(R.id.stations_departure_stops_entry_delay);
            positionView = itemView.findViewById(R.id.stations_departure_stops_entry_position);
            nameView = itemView.findViewById(R.id.stations_departure_stops_entry_name);
            pearlView = itemView.findViewById(R.id.stations_departure_stops_entry_pearl);
        }

        void bind(final StopRow row, final NetworkId network) {
            final Context context = itemView.getContext();
            final Stop stop = row.stop;

            final boolean isFirst = row.type == PearlView.Type.DEPARTURE;
            final boolean isLast = row.type == PearlView.Type.ARRIVAL;
            final boolean useDeparture;
            if (isFirst)
                useDeparture = true;
            else if (isLast)
                useDeparture = false;
            else
                useDeparture = stop.plannedArrivalTime == null;

            final Date time;
            final boolean predicted;
            final boolean cancelled;
            final Long delay;
            final Position position;
            if (useDeparture) {
                time = stop.getDepartureTime();
                predicted = stop.isDepartureTimePredicted();
                cancelled = stop.departureCancelled;
                delay = stop.getDepartureDelay();
                position = stop.getDeparturePosition();
            } else {
                time = stop.getArrivalTime();
                predicted = stop.isArrivalTimePredicted();
                cancelled = stop.arrivalCancelled;
                delay = stop.getArrivalDelay();
                position = stop.getArrivalPosition();
            }

            // date
            dateView.setText(row.date);

            // time
            timeView.setText(time != null ? Formats.formatTime(context, time.getTime()) : null);
            timeView.setTypeface(Typeface.DEFAULT, Typeface.BOLD + (predicted ? Typeface.ITALIC : 0));
            setStrikeThru(timeView, cancelled);

            // delay
            if (delay != null && delay != 0) {
                final long delayMins = delay / DateUtils.MINUTE_IN_MILLIS;
                delayView.setText(String.format(Locale.US, "(%+d)", delayMins));
                delayView.setVisibility(View.VISIBLE);
            } else {
                delayView.setText(null);
                delayView.setVisibility(View.GONE);
            }

            // position
            positionView.setText(position != null && !cancelled ? position.toString() : null);
            positionView.setVisibility(position != null && !cancelled ? View.VISIBLE : View.GONE);

            // name
            nameView.setText(stop.location.uniqueShortName());
            setStrikeThru(nameView, cancelled);
            final boolean hasId = stop.location.hasId();
            if (hasId)
                itemView.setOnClickListener(v -> StationDetailsActivity.start(v.getContext(), network,
                        stop.location));
            else
                itemView.setOnClickListener(null);
            itemView.setFocusable(hasId);

            // pearl
            pearlView.setType(row.type);
            pearlView.setStyle(row.style);
            pearlView.setFontMetrics(nameView.getPaint().getFontMetrics());
        }

        private static void setStrikeThru(final TextView view, final boolean strikeThru) {
            if (strikeThru)
                view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            else
                view.setPaintFlags(view.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }
    }
}
