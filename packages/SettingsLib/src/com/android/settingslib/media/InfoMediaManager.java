/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settingslib.media;

import static android.media.MediaRoute2Info.TYPE_BLE_HEADSET;
import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_HDMI_ARC;
import static android.media.MediaRoute2Info.TYPE_HDMI_EARC;
import static android.media.MediaRoute2Info.TYPE_HEARING_AID;
import static android.media.MediaRoute2Info.TYPE_REMOTE_AUDIO_VIDEO_RECEIVER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_CAR;
import static android.media.MediaRoute2Info.TYPE_REMOTE_COMPUTER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_GAME_CONSOLE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SMARTPHONE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SMARTWATCH;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET_DOCKED;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_UNKNOWN;
import static android.media.MediaRoute2Info.TYPE_USB_ACCESSORY;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_USB_HEADSET;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import static com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_SELECTED;

import android.annotation.TargetApi;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.DoNotInline;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.flags.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** InfoMediaManager provide interface to get InfoMediaDevice list. */
@RequiresApi(Build.VERSION_CODES.R)
public abstract class InfoMediaManager extends MediaManager {

    private static final String TAG = "InfoMediaManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /** Checked exception that signals the specified package is not present in the system. */
    public static class PackageNotAvailableException extends Exception {
        public PackageNotAvailableException(String message) {
            super(message);
        }
    }

    @NonNull protected final String mPackageName;
    private MediaDevice mCurrentConnectedDevice;
    private final LocalBluetoothManager mBluetoothManager;
    private final Map<String, RouteListingPreference.Item> mPreferenceItemMap =
            new ConcurrentHashMap<>();

    /* package */ InfoMediaManager(
            Context context,
            @NonNull String packageName,
            Notification notification,
            LocalBluetoothManager localBluetoothManager) {
        super(context, notification);

        mBluetoothManager = localBluetoothManager;
        mPackageName = packageName;
    }

    /** Creates an instance of InfoMediaManager. */
    public static InfoMediaManager createInstance(
            Context context,
            @Nullable String packageName,
            Notification notification,
            LocalBluetoothManager localBluetoothManager) {

        // The caller is only interested in system routes (headsets, built-in speakers, etc), and is
        // not interested in a specific app's routing. The media routing APIs still require a
        // package name, so we use the package name of the calling app.
        if (TextUtils.isEmpty(packageName)) {
            packageName = context.getPackageName();
        }

        if (Flags.useMediaRouter2ForInfoMediaManager()) {
            try {
                return new RouterInfoMediaManager(
                        context, packageName, notification, localBluetoothManager);
            } catch (PackageNotAvailableException ex) {
                // TODO: b/293578081 - Propagate this exception to callers for proper handling.
                Log.w(TAG, "Returning a no-op InfoMediaManager for package " + packageName);
                return new NoOpInfoMediaManager(
                        context, packageName, notification, localBluetoothManager);
            }
        } else {
            return new ManagerInfoMediaManager(
                    context, packageName, notification, localBluetoothManager);
        }
    }

    public void startScan() {
        mMediaDevices.clear();
        startScanOnRouter();
        updateRouteListingPreference();
        refreshDevices();
    }

    private void updateRouteListingPreference() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            RouteListingPreference routeListingPreference =
                    getRouteListingPreference();
            Api34Impl.onRouteListingPreferenceUpdated(routeListingPreference,
                    mPreferenceItemMap);
        }
    }

    public abstract void stopScan();

    protected abstract void startScanOnRouter();

    protected abstract void transferToRoute(@NonNull MediaRoute2Info route);

    protected abstract void selectRoute(
            @NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info);

    protected abstract void deselectRoute(
            @NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info);

    protected abstract void releaseSession(@NonNull RoutingSessionInfo sessionInfo);

    @NonNull
    protected abstract List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo info);

    @NonNull
    protected abstract List<MediaRoute2Info> getDeselectableRoutes(
            @NonNull RoutingSessionInfo info);

    @NonNull
    protected abstract List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo info);

    protected abstract void setSessionVolume(@NonNull RoutingSessionInfo info, int volume);

    protected abstract void setRouteVolume(@NonNull MediaRoute2Info route, int volume);

    @Nullable
    protected abstract RouteListingPreference getRouteListingPreference();

    /**
     * Returns the list of remote {@link RoutingSessionInfo routing sessions} known to the system.
     */
    @NonNull
    protected abstract List<RoutingSessionInfo> getRemoteSessions();

    /**
     * Returns a non-empty list containing the routing sessions associated to the target media app.
     *
     * <p> The first item of the list is always the {@link RoutingSessionInfo#isSystemSession()
     * system session}, followed other remote sessions linked to the target media app.
     */
    @NonNull
    protected abstract List<RoutingSessionInfo> getRoutingSessionsForPackage();

    @Nullable
    protected abstract RoutingSessionInfo getRoutingSessionById(@NonNull String sessionId);

    @NonNull
    protected abstract List<MediaRoute2Info> getAvailableRoutesFromRouter();

    @NonNull
    protected abstract List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName);

    protected final void rebuildDeviceList() {
        mMediaDevices.clear();
        mCurrentConnectedDevice = null;
        buildAvailableRoutes();
    }

    protected final void notifyCurrentConnectedDeviceChanged() {
        final String id = mCurrentConnectedDevice != null ? mCurrentConnectedDevice.getId() : null;
        dispatchConnectedDeviceChanged(id);
    }

    @RequiresApi(34)
    protected final void notifyRouteListingPreferenceUpdated(
            RouteListingPreference routeListingPreference) {
        Api34Impl.onRouteListingPreferenceUpdated(routeListingPreference, mPreferenceItemMap);
    }

    /**
     * Get current device that played media.
     * @return MediaDevice
     */
    MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    /* package */ void connectToDevice(MediaDevice device) {
        if (device.mRouteInfo == null) {
            Log.w(TAG, "Unable to connect. RouteInfo is empty");
            return;
        }

        device.setConnectedRecord();
        transferToRoute(device.mRouteInfo);
    }

    /**
     * Add a MediaDevice to let it play current media.
     *
     * @param device MediaDevice
     * @return If add device successful return {@code true}, otherwise return {@code false}
     */
    boolean addDeviceToPlayMedia(MediaDevice device) {
        final RoutingSessionInfo info = getActiveRoutingSession();
        if (!info.getSelectableRoutes().contains(device.mRouteInfo.getId())) {
            Log.w(TAG, "addDeviceToPlayMedia() Ignoring selecting a non-selectable device : "
                    + device.getName());
            return false;
        }

        selectRoute(device.mRouteInfo, info);
        return true;
    }

    @NonNull
    private RoutingSessionInfo getActiveRoutingSession() {
        // List is never empty.
        final List<RoutingSessionInfo> sessions = getRoutingSessionsForPackage();
        if (sessions.size() <= 0) {
            // Return system session.
            sessions.get(0);
        }
        return sessions.get(sessions.size() - 1);
    }

    boolean isRoutingSessionAvailableForVolumeControl() {
        List<RoutingSessionInfo> sessions = getRoutingSessionsForPackage();

        for (RoutingSessionInfo session : sessions) {
            if (!session.isSystemSession()
                    && session.getVolumeHandling() != MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                return true;
            }
        }

        Log.d(TAG, "No routing session for " + mPackageName);
        return false;
    }

    boolean preferRouteListingOrdering() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && Api34Impl.preferRouteListingOrdering(getRouteListingPreference());
    }

    @Nullable
    ComponentName getLinkedItemComponentName() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && TextUtils.isEmpty(
                mPackageName)) {
            return null;
        }
        return Api34Impl.getLinkedItemComponentName(getRouteListingPreference());
    }

    /**
     * Remove a {@code device} from current media.
     *
     * @param device MediaDevice
     * @return If device stop successful return {@code true}, otherwise return {@code false}
     */
    boolean removeDeviceFromPlayMedia(MediaDevice device) {
        final RoutingSessionInfo info = getActiveRoutingSession();
        if (!info.getSelectedRoutes().contains(device.mRouteInfo.getId())) {
            Log.w(TAG, "removeDeviceFromMedia() Ignoring deselecting a non-deselectable device : "
                    + device.getName());
            return false;
        }

        deselectRoute(device.mRouteInfo, info);
        return true;
    }

    /**
     * Release session to stop playing media on MediaDevice.
     */
    boolean releaseSession() {
        releaseSession(getActiveRoutingSession());
        return true;
    }

    /**
     * Returns the list of {@link MediaDevice media devices} that can be added to the current {@link
     * RoutingSessionInfo routing session}.
     */
    @NonNull
    List<MediaDevice> getSelectableMediaDevices() {
        final RoutingSessionInfo info = getActiveRoutingSession();

        final List<MediaDevice> deviceList = new ArrayList<>();
        for (MediaRoute2Info route : getSelectableRoutes(info)) {
            deviceList.add(
                    new InfoMediaDevice(
                            mContext, route, mPreferenceItemMap.get(route.getId())));
        }
        return deviceList;
    }

    /**
     * Returns the list of {@link MediaDevice media devices} that can be deselected from the current
     * {@link RoutingSessionInfo routing session}.
     */
    @NonNull
    List<MediaDevice> getDeselectableMediaDevices() {
        final RoutingSessionInfo info = getActiveRoutingSession();

        final List<MediaDevice> deviceList = new ArrayList<>();
        for (MediaRoute2Info route : getDeselectableRoutes(info)) {
            deviceList.add(
                    new InfoMediaDevice(
                            mContext, route, mPreferenceItemMap.get(route.getId())));
            Log.d(TAG, route.getName() + " is deselectable for " + mPackageName);
        }
        return deviceList;
    }

    /**
     * Returns the list of {@link MediaDevice media devices} that are selected in the current {@link
     * RoutingSessionInfo routing session}.
     */
    @NonNull
    List<MediaDevice> getSelectedMediaDevices() {
        RoutingSessionInfo info = getActiveRoutingSession();

        final List<MediaDevice> deviceList = new ArrayList<>();
        for (MediaRoute2Info route : getSelectedRoutes(info)) {
            deviceList.add(
                    new InfoMediaDevice(
                            mContext, route, mPreferenceItemMap.get(route.getId())));
        }
        return deviceList;
    }

    /* package */ void adjustDeviceVolume(MediaDevice device, int volume) {
        if (device.mRouteInfo == null) {
            Log.w(TAG, "Unable to set volume. RouteInfo is empty");
            return;
        }
        setRouteVolume(device.mRouteInfo, volume);
    }

    void adjustSessionVolume(RoutingSessionInfo info, int volume) {
        if (info == null) {
            Log.w(TAG, "Unable to adjust session volume. RoutingSessionInfo is empty");
            return;
        }

        setSessionVolume(info, volume);
    }

    /**
     * Adjust the volume of {@link android.media.RoutingSessionInfo}.
     *
     * @param volume the value of volume
     */
    void adjustSessionVolume(int volume) {
        Log.d(TAG, "adjustSessionVolume() adjust volume: " + volume + ", with : " + mPackageName);
        setSessionVolume(getActiveRoutingSession(), volume);
    }

    /**
     * Gets the maximum volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return  maximum volume of the session, and return -1 if not found.
     */
    public int getSessionVolumeMax() {
        return getActiveRoutingSession().getVolumeMax();
    }

    /**
     * Gets the current volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return current volume of the session, and return -1 if not found.
     */
    public int getSessionVolume() {
        return getActiveRoutingSession().getVolume();
    }

    CharSequence getSessionName() {
        return getActiveRoutingSession().getName();
    }

    @TargetApi(Build.VERSION_CODES.R)
    boolean shouldEnableVolumeSeekBar(RoutingSessionInfo sessionInfo) {
        return sessionInfo.isSystemSession() // System sessions are not remote
                || sessionInfo.getVolumeHandling() != MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
    }

    protected final synchronized void refreshDevices() {
        rebuildDeviceList();
        dispatchDeviceListAdded();
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    private synchronized void buildAvailableRoutes() {
        for (MediaRoute2Info route : getAvailableRoutes()) {
            if (DEBUG) {
                Log.d(TAG, "buildAvailableRoutes() route : " + route.getName() + ", volume : "
                        + route.getVolume() + ", type : " + route.getType());
            }
            addMediaDevice(route);
        }
    }
    private synchronized List<MediaRoute2Info> getAvailableRoutes() {
        List<MediaRoute2Info> availableRoutes = new ArrayList<>();
        RoutingSessionInfo activeSession = getActiveRoutingSession();

        List<MediaRoute2Info> selectedRoutes = getSelectedRoutes(activeSession);
        availableRoutes.addAll(selectedRoutes);
        availableRoutes.addAll(getSelectableRoutes(activeSession));

        final List<MediaRoute2Info> transferableRoutes = getTransferableRoutes(mPackageName);
        for (MediaRoute2Info transferableRoute : transferableRoutes) {
            boolean alreadyAdded = false;
            for (MediaRoute2Info mediaRoute2Info : availableRoutes) {
                if (TextUtils.equals(transferableRoute.getId(), mediaRoute2Info.getId())) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) {
                availableRoutes.add(transferableRoute);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            RouteListingPreference routeListingPreference = getRouteListingPreference();
            if (routeListingPreference != null) {
                final List<RouteListingPreference.Item> preferenceRouteListing =
                        Api34Impl.composePreferenceRouteListing(
                                routeListingPreference);
                availableRoutes = Api34Impl.arrangeRouteListByPreference(selectedRoutes,
                        getAvailableRoutesFromRouter(),
                                preferenceRouteListing);
            }
            return Api34Impl.filterDuplicatedIds(availableRoutes);
        } else {
            return availableRoutes;
        }
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @VisibleForTesting
    void addMediaDevice(MediaRoute2Info route) {
        final int deviceType = route.getType();
        MediaDevice mediaDevice = null;
        switch (deviceType) {
            case TYPE_UNKNOWN:
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            case TYPE_GROUP:
            case TYPE_REMOTE_TABLET:
            case TYPE_REMOTE_TABLET_DOCKED:
            case TYPE_REMOTE_COMPUTER:
            case TYPE_REMOTE_GAME_CONSOLE:
            case TYPE_REMOTE_CAR:
            case TYPE_REMOTE_SMARTWATCH:
            case TYPE_REMOTE_SMARTPHONE:
                mediaDevice =
                        new InfoMediaDevice(
                                mContext,
                                route,
                                mPreferenceItemMap.get(route.getId()));
                break;
            case TYPE_BUILTIN_SPEAKER:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
            case TYPE_DOCK:
            case TYPE_HDMI:
            case TYPE_HDMI_ARC:
            case TYPE_HDMI_EARC:
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                mediaDevice =
                        new PhoneMediaDevice(
                                mContext,
                                route,
                                mPreferenceItemMap.getOrDefault(route.getId(), null));
                break;
            case TYPE_HEARING_AID:
            case TYPE_BLUETOOTH_A2DP:
            case TYPE_BLE_HEADSET:
                final BluetoothDevice device =
                        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(route.getAddress());
                final CachedBluetoothDevice cachedDevice =
                        mBluetoothManager.getCachedDeviceManager().findDevice(device);
                if (cachedDevice != null) {
                    mediaDevice =
                            new BluetoothMediaDevice(
                                    mContext,
                                    cachedDevice,
                                    route,
                                    mPreferenceItemMap.getOrDefault(route.getId(), null));
                }
                break;
            case TYPE_REMOTE_AUDIO_VIDEO_RECEIVER:
                mediaDevice =
                        new ComplexMediaDevice(
                                mContext,
                                route,
                                mPreferenceItemMap.get(route.getId()));
                break;
            default:
                Log.w(TAG, "addMediaDevice() unknown device type : " + deviceType);
                break;
        }

        if (mediaDevice != null
                && getActiveRoutingSession().getSelectedRoutes().contains(route.getId())) {
            mediaDevice.setState(STATE_SELECTED);
            if (mCurrentConnectedDevice == null) {
                mCurrentConnectedDevice = mediaDevice;
            }
        }
        if (mediaDevice != null) {
            mMediaDevices.add(mediaDevice);
        }
    }

    @RequiresApi(34)
    static class Api34Impl {
        @DoNotInline
        static List<RouteListingPreference.Item> composePreferenceRouteListing(
                RouteListingPreference routeListingPreference) {
            List<RouteListingPreference.Item> finalizedItemList = new ArrayList<>();
            List<RouteListingPreference.Item> itemList = routeListingPreference.getItems();
            for (RouteListingPreference.Item item : itemList) {
                // Put suggested devices on the top first before further organization
                if ((item.getFlags() & RouteListingPreference.Item.FLAG_SUGGESTED) != 0) {
                    finalizedItemList.add(0, item);
                } else {
                    finalizedItemList.add(item);
                }
            }
            return finalizedItemList;
        }

        @DoNotInline
        static synchronized List<MediaRoute2Info> filterDuplicatedIds(List<MediaRoute2Info> infos) {
            List<MediaRoute2Info> filteredInfos = new ArrayList<>();
            Set<String> foundDeduplicationIds = new HashSet<>();
            for (MediaRoute2Info mediaRoute2Info : infos) {
                if (!Collections.disjoint(mediaRoute2Info.getDeduplicationIds(),
                        foundDeduplicationIds)) {
                    continue;
                }
                filteredInfos.add(mediaRoute2Info);
                foundDeduplicationIds.addAll(mediaRoute2Info.getDeduplicationIds());
            }
            return filteredInfos;
        }

        /**
         * Returns an ordered list of available devices based on the provided {@code
         * routeListingPreferenceItems}.
         *
         * <p>The result has the following order:
         *
         * <ol>
         *   <li>Selected routes.
         *   <li>Not-selected system routes.
         *   <li>Not-selected, non-system, available routes sorted by route listing preference.
         * </ol>
         *
         * @param selectedRoutes List of currently selected routes.
         * @param availableRoutes List of available routes that match the app's requested route
         *     features.
         * @param routeListingPreferenceItems Ordered list of {@link RouteListingPreference.Item} to
         *     sort routes with.
         */
        @DoNotInline
        static List<MediaRoute2Info> arrangeRouteListByPreference(
                List<MediaRoute2Info> selectedRoutes,
                List<MediaRoute2Info> availableRoutes,
                List<RouteListingPreference.Item> routeListingPreferenceItems) {
            Set<String> sortedRouteIds = new LinkedHashSet<>();

            // Add selected routes first.
            for (MediaRoute2Info selectedRoute : selectedRoutes) {
                sortedRouteIds.add(selectedRoute.getId());
            }

            // Add not-yet-added system routes.
            for (MediaRoute2Info availableRoute : availableRoutes) {
                if (availableRoute.isSystemRoute()) {
                    sortedRouteIds.add(availableRoute.getId());
                }
            }

            // Create a mapping from id to route to avoid a quadratic search.
            Map<String, MediaRoute2Info> idToRouteMap =
                    Stream.concat(selectedRoutes.stream(), availableRoutes.stream())
                            .collect(
                                    Collectors.toMap(
                                            MediaRoute2Info::getId,
                                            Function.identity(),
                                            (route1, route2) -> route1));

            // Add not-selected routes that match RLP items. All system routes have already been
            // added at this point.
            for (RouteListingPreference.Item item : routeListingPreferenceItems) {
                MediaRoute2Info route = idToRouteMap.get(item.getRouteId());
                if (route != null) {
                    sortedRouteIds.add(route.getId());
                }
            }

            return sortedRouteIds.stream().map(idToRouteMap::get).collect(Collectors.toList());
        }

        @DoNotInline
        static boolean preferRouteListingOrdering(RouteListingPreference routeListingPreference) {
            return routeListingPreference != null
                    && !routeListingPreference.getUseSystemOrdering();
        }

        @DoNotInline
        @Nullable
        static ComponentName getLinkedItemComponentName(
                RouteListingPreference routeListingPreference) {
            return routeListingPreference == null ? null
                    : routeListingPreference.getLinkedItemComponentName();
        }

        @DoNotInline
        static void onRouteListingPreferenceUpdated(
                RouteListingPreference routeListingPreference,
                Map<String, RouteListingPreference.Item> preferenceItemMap) {
            preferenceItemMap.clear();
            if (routeListingPreference != null) {
                routeListingPreference.getItems().forEach((item) ->
                        preferenceItemMap.put(item.getRouteId(), item));
            }
        }
    }
}
