package wangdaye.com.geometricweather.main;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProviders;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.google.android.material.appbar.AppBarLayout;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import wangdaye.com.geometricweather.basic.GeoActivity;
import wangdaye.com.geometricweather.basic.model.location.Location;
import wangdaye.com.geometricweather.R;
import wangdaye.com.geometricweather.basic.model.option.DarkMode;
import wangdaye.com.geometricweather.basic.model.option.provider.WeatherSource;
import wangdaye.com.geometricweather.basic.model.resource.Resource;
import wangdaye.com.geometricweather.main.ui.MainColorPicker;
import wangdaye.com.geometricweather.main.ui.MainLayoutManager;
import wangdaye.com.geometricweather.main.ui.adapter.main.MainAdapter;
import wangdaye.com.geometricweather.main.ui.dialog.LocationHelpDialog;
import wangdaye.com.geometricweather.resource.provider.ResourceProvider;
import wangdaye.com.geometricweather.resource.provider.ResourcesProviderFactory;
import wangdaye.com.geometricweather.settings.SettingsOptionManager;
import wangdaye.com.geometricweather.ui.widget.verticalScrollView.VerticalRecyclerView;
import wangdaye.com.geometricweather.ui.widget.weatherView.WeatherView;
import wangdaye.com.geometricweather.ui.widget.weatherView.WeatherViewController;
import wangdaye.com.geometricweather.ui.widget.weatherView.circularSkyView.CircularSkyWeatherView;
import wangdaye.com.geometricweather.ui.widget.weatherView.materialWeatherView.MaterialWeatherView;
import wangdaye.com.geometricweather.remoteviews.NotificationUtils;
import wangdaye.com.geometricweather.remoteviews.WidgetUtils;
import wangdaye.com.geometricweather.utils.helpter.IntentHelper;
import wangdaye.com.geometricweather.utils.SnackbarUtils;
import wangdaye.com.geometricweather.ui.widget.InkPageIndicator;
import wangdaye.com.geometricweather.ui.widget.SwipeSwitchLayout;
import wangdaye.com.geometricweather.utils.DisplayUtils;
import wangdaye.com.geometricweather.background.polling.PollingManager;
import wangdaye.com.geometricweather.utils.manager.ThreadManager;
import wangdaye.com.geometricweather.utils.manager.TimeManager;
import wangdaye.com.geometricweather.ui.widget.verticalScrollView.VerticalSwipeRefreshLayout;
import wangdaye.com.geometricweather.utils.manager.ShortcutsManager;

/**
 * Main activity.
 * */

public class MainActivity extends GeoActivity
        implements SwipeRefreshLayout.OnRefreshListener {

    private MainActivityViewModel viewModel;

    private WeatherView weatherView;
    private AppBarLayout appBar;
    private Toolbar toolbar;

    private InkPageIndicator indicator;

    private SwipeSwitchLayout switchLayout;
    private VerticalSwipeRefreshLayout refreshLayout;
    private VerticalRecyclerView recyclerView;

    @Nullable private MainAdapter adapter;
    @Nullable private AnimatorSet recyclerViewAnimator;

    private ResourceProvider resourceProvider;
    private MainColorPicker colorPicker;

    @Nullable private String currentLocationFormattedId;
    @Nullable private WeatherSource currentWeatherSource;
    private long currentWeatherTimeStamp;

    public static final int SETTINGS_ACTIVITY = 1;
    public static final int MANAGE_ACTIVITY = 2;
    public static final int CARD_MANAGE_ACTIVITY = 3;

    private static final long INVALID_CURRENT_WEATHER_TIME_STAMP = -1;

    public static final String KEY_MAIN_ACTIVITY_LOCATION_FORMATTED_ID
            = "MAIN_ACTIVITY_LOCATION_FORMATTED_ID";

    public static final String ACTION_UPDATE_WEATHER_IN_BACKGROUND
            = "com.wangdaye.geomtricweather.ACTION_UPDATE_WEATHER_IN_BACKGROUND";
    public static final String KEY_LOCATION_FORMATTED_ID = "LOCATION_FORMATTED_ID";

    private BroadcastReceiver backgroundUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String formattedId = intent.getStringExtra(KEY_LOCATION_FORMATTED_ID);
            viewModel.updateLocationFromBackground(MainActivity.this, formattedId);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // attach weather view.
        switch (SettingsOptionManager.getInstance(this).getUiStyle()) {
            case MATERIAL:
                weatherView = new MaterialWeatherView(this);
                break;

            case CIRCULAR:
                weatherView = new CircularSkyWeatherView(this);
                break;
        }
        ((FrameLayout) findViewById(R.id.activity_main_background)).addView(
                (View) weatherView,
                0,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        resetUIUpdateFlag();
        ensureResourceProvider();
        ensureColorPicker();

        initModel();
        initView();

        registerReceiver(
                backgroundUpdateReceiver,
                new IntentFilter(ACTION_UPDATE_WEATHER_IN_BACKGROUND)
        );
        refreshBackgroundViews(true, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetUIUpdateFlag();
        viewModel.init(this, getLocationId(intent));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case SETTINGS_ACTIVITY:
                ensureResourceProvider();
                ensureColorPicker();

                Location location = viewModel.getCurrentLocationValue();
                if (location != null) {
                    ThreadManager.getInstance().execute(() ->
                            NotificationUtils.updateNotificationIfNecessary(this, location));
                }
                resetUIUpdateFlag();
                viewModel.reset(this);

                refreshBackgroundViews(true, location);
                break;

            case MANAGE_ACTIVITY:
                if (resultCode == RESULT_OK) {
                    viewModel.init(this, getLocationId(data));
                }
                break;

            case CARD_MANAGE_ACTIVITY:
                if (resultCode == RESULT_OK) {
                    resetUIUpdateFlag();
                    viewModel.reset(this);
                }
                break;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        weatherView.setDrawable(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        weatherView.setDrawable(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(backgroundUpdateReceiver);
    }

    @Override
    public View getSnackbarContainer() {
        return switchLayout;
    }

    // init.

    private void initModel() {
        viewModel = ViewModelProviders.of(this).get(MainActivityViewModel.class);
        viewModel.init(this, getLocationId(getIntent()));
    }

    @Nullable
    private String getLocationId(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        return intent.getStringExtra(KEY_MAIN_ACTIVITY_LOCATION_FORMATTED_ID);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initView() {
        this.appBar = findViewById(R.id.activity_main_appBar);

        this.toolbar = findViewById(R.id.activity_main_toolbar);
        toolbar.inflateMenu(R.menu.activity_main);
        toolbar.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case R.id.action_manage:
                    IntentHelper.startManageActivityForResult(
                            this, viewModel.getCurrentLocationFormattedId());
                    break;

                case R.id.action_settings:
                    IntentHelper.startSettingsActivityForResult(this);
                    break;
            }
            return true;
        });

        this.switchLayout = findViewById(R.id.activity_main_switchView);
        switchLayout.setOnSwitchListener(switchListener);

        this.refreshLayout = findViewById(R.id.activity_main_refreshView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            refreshLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                int startPosition = insets.getSystemWindowInsetTop()
                        + getResources().getDimensionPixelSize(R.dimen.normal_margin);
                refreshLayout.setProgressViewOffset(
                        false,
                        startPosition,
                        (int) (startPosition + 64 * getResources().getDisplayMetrics().density)
                );
                return insets;
            });
        }

        refreshLayout.setOnRefreshListener(this);

        this.recyclerView = findViewById(R.id.activity_main_recyclerView);
        recyclerView.setLayoutManager(new MainLayoutManager());
        recyclerView.setOnTouchListener(indicatorStateListener);

        this.indicator = findViewById(R.id.activity_main_indicator);
        indicator.setSwitchView(switchLayout);

        viewModel.getCurrentLocation().observe(this, resource -> {

            setRefreshing(resource.status == Resource.Status.LOADING);
            drawUI(resource.data, resource.isDefaultLocation(), resource.isUpdatedInBackground());

            if (resource.isLocateFailed()) {
                SnackbarUtils.showSnackbar(
                        this,
                        getString(R.string.feedback_location_failed),
                        getString(R.string.help),
                        v -> {
                            if (isForeground()) {
                                new LocationHelpDialog()
                                        .setColorPicker(colorPicker)
                                        .show(getSupportFragmentManager(), null);
                            }
                        }
                );
            } else if (resource.status == Resource.Status.ERROR) {
                SnackbarUtils.showSnackbar(this, getString(R.string.feedback_get_weather_failed));
            }
        });

        viewModel.getIndicator().observe(this, resource -> {
            if (switchLayout.getTotalCount() != resource.total
                    || switchLayout.getPosition() != resource.index) {
                switchLayout.setData(resource.index, resource.total);
                indicator.setSwitchView(switchLayout);
            }

            if (resource.total > 1) {
                indicator.setVisibility(View.VISIBLE);
            } else {
                indicator.setVisibility(View.GONE);
            }
        });
    }

    // control.

    @SuppressLint("SetTextI18n")
    private void drawUI(Location location, boolean defaultLocation, boolean updatedInBackground) {
        if (location.equals(currentLocationFormattedId)
                && location.getWeatherSource() == currentWeatherSource
                && location.getWeather() != null
                && location.getWeather().getBase().getTimeStamp() == currentWeatherTimeStamp) {
            return;
        }

        boolean needToResetUI = !location.equals(currentLocationFormattedId)
                || currentWeatherSource != location.getWeatherSource()
                || currentWeatherTimeStamp != INVALID_CURRENT_WEATHER_TIME_STAMP;

        currentLocationFormattedId = location.getFormattedId();
        currentWeatherSource = location.getWeatherSource();
        currentWeatherTimeStamp = location.getWeather() != null
                ? location.getWeather().getBase().getTimeStamp()
                : INVALID_CURRENT_WEATHER_TIME_STAMP;

        DisplayUtils.setSystemBarStyle(this, getWindow(), true,
                false, false, false, false);

        if (location.getWeather() == null) {
            resetUI(location);
            return;
        }

        if (needToResetUI) {
            resetUI(location);
        }

        boolean oldDaytime = TimeManager.getInstance(this).isDayTime();
        boolean daytime = TimeManager.getInstance(this)
                .update(this, location)
                .isDayTime();

        setDarkMode(daytime);
        if (oldDaytime != daytime) {
            ensureColorPicker();
        }

        WeatherViewController.setWeatherCode(
                weatherView, location.getWeather(), daytime, resourceProvider);

        DisplayUtils.setWindowTopColor(this, weatherView.getThemeColors(colorPicker.isLightTheme())[0]);

        refreshLayout.setColorSchemeColors(weatherView.getThemeColors(colorPicker.isLightTheme())[0]);
        refreshLayout.setProgressBackgroundColorSchemeColor(colorPicker.getRootColor(this));

        boolean listAnimationEnabled = SettingsOptionManager.getInstance(this).isListAnimationEnabled();
        boolean itemAnimationEnabled = SettingsOptionManager.getInstance(this).isItemAnimationEnabled();

        adapter = new MainAdapter(this, location, weatherView, resourceProvider, colorPicker,
                listAnimationEnabled, itemAnimationEnabled);
        recyclerView.setAdapter(adapter);
        recyclerView.clearOnScrollListeners();
        recyclerView.addOnScrollListener(new OnScrollListener());

        indicator.setCurrentIndicatorColor(colorPicker.getAccentColor(this));
        indicator.setIndicatorColor(colorPicker.getTextSubtitleColor(this));

        if (!listAnimationEnabled) {
            recyclerView.setAlpha(0);
            recyclerViewAnimator = new AnimatorSet();
            recyclerViewAnimator.playTogether(
                    ObjectAnimator.ofFloat(recyclerView, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(
                            recyclerView,
                            "translationY",
                            DisplayUtils.dpToPx(this, 40), 0f
                    )
            );
            recyclerViewAnimator.setDuration(450);
            recyclerViewAnimator.setInterpolator(new DecelerateInterpolator(2f));
            recyclerViewAnimator.setStartDelay(150);
            recyclerViewAnimator.start();
        }

        refreshBackgroundViews(
                false,
                (!updatedInBackground && defaultLocation) ? location : null
        );
    }

    private void resetUI(Location location) {
        if (weatherView.getWeatherKind() == WeatherView.WEATHER_KING_NULL
                && location.getWeather() == null) {
            WeatherViewController.setWeatherCode(
                    weatherView, null, colorPicker.isLightTheme(), resourceProvider);
            refreshLayout.setColorSchemeColors(weatherView.getThemeColors(colorPicker.isLightTheme())[0]);
            refreshLayout.setProgressBackgroundColorSchemeColor(colorPicker.getRootColor(this));
        }
        weatherView.setGravitySensorEnabled(
                SettingsOptionManager.getInstance(this).isGravitySensorEnabled());

        toolbar.setTitle(location.getCityName(this));

        switchLayout.reset();

        if (recyclerViewAnimator != null) {
            recyclerViewAnimator.cancel();
            recyclerViewAnimator = null;
        }
        if (adapter != null) {
            recyclerView.setAdapter(null);
            adapter = null;
        }
    }

    private void resetUIUpdateFlag() {
        currentLocationFormattedId = null;
        currentWeatherSource = null;
        currentWeatherTimeStamp = INVALID_CURRENT_WEATHER_TIME_STAMP;
    }

    private void ensureResourceProvider() {
        String iconProvider = SettingsOptionManager.getInstance(this).getIconProvider();
        if (resourceProvider == null
                || !resourceProvider.getPackageName().equals(iconProvider)) {
            resourceProvider = ResourcesProviderFactory.getNewInstance();
        }
    }

    private void ensureColorPicker() {
        boolean daytime = TimeManager.getInstance(this).isDayTime();
        DarkMode darkMode = SettingsOptionManager.getInstance(this).getDarkMode();
        if (colorPicker == null
                || colorPicker.isDaytime() != daytime
                || !colorPicker.getDarkMode().equals(darkMode)) {
            colorPicker = new MainColorPicker(daytime, darkMode);
        }
    }

    @SuppressLint("RestrictedApi")
    private void setDarkMode(boolean dayTime) {
        if (SettingsOptionManager.getInstance(this).getDarkMode() == DarkMode.AUTO) {
            int mode = dayTime ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
            getDelegate().setLocalNightMode(mode);
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    private void setRefreshing(final boolean b) {
        refreshLayout.post(() -> refreshLayout.setRefreshing(b));
    }

    private void refreshBackgroundViews(boolean resetBackground, @Nullable Location location) {
        if (resetBackground) {
            Observable.create(emitter -> PollingManager.resetAllBackgroundTask(this, false))
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .delay(1, TimeUnit.SECONDS)
                    .subscribe();
        }

        if (location != null) {
            Observable.create(emitter -> {
                WidgetUtils.updateWidgetIfNecessary(this, location);
                NotificationUtils.updateNotificationIfNecessary(this, location);
            }).subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .delay(1, TimeUnit.SECONDS)
                    .subscribe();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            ShortcutsManager.refreshShortcutsInNewThread(this, viewModel.getLocationList());
        }
    }

    // interface.

    // on touch listener.

    private View.OnTouchListener indicatorStateListener = new View.OnTouchListener() {

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_MOVE:
                    indicator.setDisplayState(true);
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    indicator.setDisplayState(false);
                    break;
            }
            return false;
        }
    };

    // on swipe listener(swipe switch layout).

    private SwipeSwitchLayout.OnSwitchListener switchListener = new SwipeSwitchLayout.OnSwitchListener() {

        private Location location;
        private boolean indexSwitched;

        private float lastProgress = 0;

        @Override
        public void onSwipeProgressChanged(int swipeDirection, float progress) {
            indicator.setDisplayState(progress != 0);

            indexSwitched = false;

            if (progress >= 1 && lastProgress < 0.5) {
                indexSwitched = true;
                location = viewModel.getLocationFromList(MainActivity.this,
                        swipeDirection == SwipeSwitchLayout.SWIPE_DIRECTION_LEFT ? 1 : -1);
                lastProgress = 1;
            } else if (progress < 0.5 && lastProgress >= 1) {
                indexSwitched = true;
                location = viewModel.getLocationFromList(MainActivity.this, 0);
                lastProgress = 0;
            }

            if (indexSwitched) {
                toolbar.setTitle(location.getCityName(MainActivity.this));
                if (location.getWeather() != null) {
                    WeatherViewController.setWeatherCode(
                            weatherView,
                            location.getWeather(),
                            TimeManager.isDaylight(location),
                            resourceProvider
                    );
                }
            }
        }

        @Override
        public void onSwipeReleased(int swipeDirection, boolean doSwitch) {
            if (doSwitch) {
                resetUIUpdateFlag();

                indicator.setDisplayState(false);
                viewModel.setLocation(
                        MainActivity.this,
                        swipeDirection == SwipeSwitchLayout.SWIPE_DIRECTION_LEFT ? 1 : -1
                );
            }
        }
    };

    // on refresh listener.

    @Override
    public void onRefresh() {
        viewModel.updateWeather(this);
    }

    // on scroll changed listener.

    private class OnScrollListener extends RecyclerView.OnScrollListener {

        private boolean topChanged;
        private boolean topOverlap;
        private boolean bottomChanged;
        private boolean bottomOverlap;

        private int firstCardMarginTop;

        private int oldScrollY;
        private int scrollY;

        OnScrollListener() {
            super();

            this.topChanged = false;
            this.topOverlap = false;
            this.bottomChanged = false;
            this.bottomOverlap = false;

            this.firstCardMarginTop = 0;

            this.oldScrollY = 0;
            this.scrollY = 0;
        }

        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            firstCardMarginTop = weatherView.getFirstCardMarginTop();

            scrollY = recyclerView.computeVerticalScrollOffset();
            oldScrollY = scrollY - dy;

            weatherView.onScroll(scrollY);
            if (adapter != null) {
                adapter.onScroll(recyclerView);
            }

            // set translation y of toolbar.
            if (adapter != null) {
                if (scrollY < firstCardMarginTop
                        - appBar.getMeasuredHeight()
                        - adapter.getCurrentTemperatureTextHeight(recyclerView)) {
                    appBar.setTranslationY(0);
                } else if (scrollY > firstCardMarginTop - appBar.getY()) {
                    appBar.setTranslationY(-appBar.getMeasuredHeight());
                } else {
                    appBar.setTranslationY(
                            firstCardMarginTop
                                    - adapter.getCurrentTemperatureTextHeight(recyclerView)
                                    - scrollY
                                    - appBar.getMeasuredHeight()
                    );
                }
            }

            // set system bar style.
            if (scrollY >= firstCardMarginTop) {
                topChanged = oldScrollY < firstCardMarginTop;
                topOverlap = true;
            } else {
                topChanged = oldScrollY >= firstCardMarginTop;
                topOverlap = false;
            }

            if (bottomOverlap != recyclerView.canScrollVertically(1)) {
                bottomOverlap = recyclerView.canScrollVertically(1);
                bottomChanged = true;
            } else {
                bottomChanged = false;
            }

            if (topChanged || bottomChanged) {
                DisplayUtils.setSystemBarStyle(
                        MainActivity.this, getWindow(), true,
                        topOverlap,
                        topOverlap && colorPicker.isLightTheme(),
                        bottomOverlap,
                        bottomOverlap && colorPicker.isLightTheme()
                );
            }
        }
    }
}