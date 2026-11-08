package com.tungsten.fcl.ui.setting;

import android.content.Context;

import com.tungsten.fcl.R;
import com.tungsten.fcl.setting.Profiles;
import com.tungsten.fcl.ui.ai.DfyAiPage;
import com.tungsten.fcl.ui.manage.VersionSettingPage;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fcllibrary.component.ui.FCLMultiPageUI;
import com.tungsten.fcllibrary.component.ui.FCLPage;
import com.tungsten.fcllibrary.component.view.FCLTabLayout;
import com.tungsten.fcllibrary.component.view.FCLUILayout;

public class SettingUI extends FCLMultiPageUI {

    public static final int PAGE_ID_SETTING_GAME = 15030;
    public static final int PAGE_ID_SETTING_LAUNCHER = 15031;
    public static final int PAGE_ID_SETTING_PLUGIN = 15032;
    public static final int PAGE_ID_SETTING_ABOUT = 15034;
    public static final int PAGE_ID_SETTING_AI = 15035;

    private FCLUILayout container;
    public FCLTabLayout tabLayout;


    public SettingUI(Context context, int id) {
        super(context, id);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tabLayout = findViewById(R.id.tab_layout);
        container = findViewById(R.id.container);
        setupPages(container, tabLayout);
    }

    @Override
    public int getPageCount() {
        return 5;
    }

    @Override
    public FCLPage createPage(int position) {
        switch (position) {
            case 1:
                return new LauncherSettingPage(getContext(), PAGE_ID_SETTING_LAUNCHER);
            case 2:
                return new PluginManagePage(getContext(), PAGE_ID_SETTING_PLUGIN);
            case 3:
                return new AboutPage(getContext(), PAGE_ID_SETTING_ABOUT);
            case 4:
                return new DfyAiPage(getContext(), PAGE_ID_SETTING_AI);
            default:
                return new VersionSettingPage(getContext(), PAGE_ID_SETTING_GAME, true);
        }
    }

    @Override
    public String[] getTabTitles() {
        return new String[]{
                getContext().getString(R.string.settings_type_global_manage),
                getContext().getString(R.string.settings_launcher),
                getContext().getString(R.string.settings_plugin),
                getContext().getString(R.string.about),
                getContext().getString(R.string.dfy_ai_tab_title)
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从系统卸载页返回等场景：同步插件管理页的插件列表（仅刷新已创建的页面）
        forEachCreatedPage(page -> {
            if (page instanceof PluginManagePage) {
                ((PluginManagePage) page).onHostResume();
            }
        });
    }

    @Override
    protected void onPageCreated(FCLPage page) {
        if (page instanceof VersionSettingPage) {
            ((VersionSettingPage) page).loadVersion(Profiles.getSelectedProfile(), null);
        }
    }

}
