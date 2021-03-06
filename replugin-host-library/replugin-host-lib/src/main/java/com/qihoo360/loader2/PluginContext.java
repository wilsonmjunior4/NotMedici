/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo360.loader2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;

import com.qihoo360.i.Factory2;
import com.qihoo360.loader.utils2.FilePermissionUtils;
import com.qihoo360.replugin.ContextInjector;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.component.service.PluginServiceClient;
import com.qihoo360.replugin.component.utils.PluginClientHelper;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * @author RePlugin Team
 */
public class PluginContext extends ContextThemeWrapper {

    private final ClassLoader mNewClassLoader;

    private final Resources mNewResources;

    private final String mPlugin;

    private final Loader mLoader;

    private final Object mSync = new Object();

    private File mFilesDir;

    private File mCacheDir;

    private File mDatabasesDir;

    private LayoutInflater mInflater;

    private ContextInjector mContextInjector;

    LayoutInflater.Factory mFactory = new LayoutInflater.Factory() {

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            return handleCreateView(name, context, attrs);
        }
    };

    public PluginContext(Context base, int themeres, ClassLoader cl, Resources r, String plugin, Loader loader) {
        super(base, themeres);

        mNewClassLoader = cl;
        mNewResources = r;
        mPlugin = plugin;
        mLoader = loader;

        mContextInjector = RePlugin.getConfig().getCallbacks().createContextInjector();
    }

    @Override
    public ClassLoader getClassLoader() {
        if (mNewClassLoader != null) {
            return mNewClassLoader;
        }
        return super.getClassLoader();
    }

    @Override
    public Resources getResources() {
        if (mNewResources != null) {
            return mNewResources;
        }
        return super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        if (mNewResources != null) {
            return mNewResources.getAssets();
        }
        return super.getAssets();
    }

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (mInflater == null) {
                LayoutInflater inflater = (LayoutInflater) super.getSystemService(name);
                // ??????????????????????????????
                mInflater = inflater.cloneInContext(this);
                mInflater.setFactory(mFactory);
                // ?????????????????????????????????????????????
                mInflater = mInflater.cloneInContext(this);
            }
            return mInflater;
        }
        return super.getSystemService(name);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        name = "plugin_" + name;
        return super.getSharedPreferences(name, mode);
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        File f = makeFilename(getFilesDir(), name);
        return new FileInputStream(f);
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        final boolean append = (mode & MODE_APPEND) != 0;
        File f = makeFilename(getFilesDir(), name);
        try {
            FileOutputStream fos = new FileOutputStream(f, append);
            setFilePermissionsFromMode(f.getPath(), mode, 0);
            return fos;
        } catch (FileNotFoundException e) {
            //
        }

        File parent = f.getParentFile();
        parent.mkdir();
        FilePermissionUtils.setPermissions(parent.getPath(), FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG, -1, -1);
        FileOutputStream fos = new FileOutputStream(f, append);
        setFilePermissionsFromMode(f.getPath(), mode, 0);
        return fos;
    }

    @Override
    public boolean deleteFile(String name) {
        File f = makeFilename(getFilesDir(), name);
        return f.delete();
    }

    @Override
    public File getFilesDir() {
        synchronized (mSync) {
            if (mFilesDir == null) {
                mFilesDir = new File(getDataDirFile(), "files");
            }
            if (!mFilesDir.exists()) {
                if (!mFilesDir.mkdirs()) {
                    if (mFilesDir.exists()) {
                        // spurious failure; probably racing with another process for this app
                        return mFilesDir;
                    }
                    if (LOGR) {
                        LogRelease.e(PLUGIN_TAG, "Unable to create files directory " + mFilesDir.getPath());
                    }
                    return null;
                }
                FilePermissionUtils.setPermissions(mFilesDir.getPath(), FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH, -1, -1);
            }
            return mFilesDir;
        }
    }

    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(getDataDirFile(), "cache");
            }
            if (!mCacheDir.exists()) {
                if (!mCacheDir.mkdirs()) {
                    if (mCacheDir.exists()) {
                        // spurious failure; probably racing with another process for this app
                        return mCacheDir;
                    }
                    if (LOGR) {
                        LogRelease.e(PLUGIN_TAG, "Unable to create cache directory " + mCacheDir.getAbsolutePath());
                    }
                    return null;
                }
                FilePermissionUtils.setPermissions(mCacheDir.getPath(), FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH, -1, -1);
            }
        }
        return mCacheDir;
    }

/*
    ???????????? Android 8.1 ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
    by cundong
    @Override
    public File getDatabasePath(String name) {
        return validateFilePath(name, false);
    }
*/

    @Override
    public File getFileStreamPath(String name) {
        return makeFilename(getFilesDir(), name);
    }

    @Override
    public File getDir(String name, int mode) {
        name = "app_" + name;
        File file = makeFilename(getDataDirFile(), name);
        if (!file.exists()) {
            file.mkdir();
            setFilePermissionsFromMode(file.getPath(), mode, FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH);
        }
        return file;
    }

    private File getDatabasesDir() {
        synchronized (mSync) {
            if (mDatabasesDir == null) {
                mDatabasesDir = new File(getDataDirFile(), "databases");
            }
            if (mDatabasesDir.getPath().equals("databases")) {
                mDatabasesDir = new File("/data/system");
            }
            return mDatabasesDir;
        }
    }

    private File validateFilePath(String name, boolean createDirectory) {
        File dir;
        File f;

        if (name.charAt(0) == File.separatorChar) {
            String dirPath = name.substring(0, name.lastIndexOf(File.separatorChar));
            dir = new File(dirPath);
            name = name.substring(name.lastIndexOf(File.separatorChar));
            f = new File(dir, name);
        } else {
            dir = getDatabasesDir();
            f = makeFilename(dir, name);
        }

        if (createDirectory && !dir.isDirectory() && dir.mkdir()) {
            FilePermissionUtils.setPermissions(dir.getPath(), FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH, -1, -1);
        }

        return f;
    }

    private final File makeFilename(File base, String name) {
        if (name.indexOf(File.separatorChar) < 0) {
            return new File(base, name);
        }
        throw new IllegalArgumentException("File " + name + " contains a path separator");
    }

    /**
     * ???????????????????????????
     *
     * @param name             ????????????????????????????????????
     * @param mode             ??????????????????
     * @param extraPermissions ??????????????????
     *                         <p>
     *                         ????????? <p>
     *                         ???????????????360????????????????????????????????????|????????????|????????????????????????????????????????????????????????????????????????????????????????????????????????? <p>
     *                         ???????????????????????????????????????????????????????????????????????????????????????????????????????????? <p>
     *                         ?????????????????????????????????????????????????????????????????????????????????????????? <p>
     * @return
     */
    private final void setFilePermissionsFromMode(String name, int mode, int extraPermissions) {
        int perms = FilePermissionUtils.S_IRUSR | FilePermissionUtils.S_IWUSR | FilePermissionUtils.S_IRGRP | FilePermissionUtils.S_IWGRP | extraPermissions;
//        if ((mode & MODE_WORLD_READABLE) != 0) {
//            perms |= FilePermissionUtils.S_IROTH;
//        }
//        if ((mode & MODE_WORLD_WRITEABLE) != 0) {
//            perms |= FilePermissionUtils.S_IWOTH;
//        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "File " + name + ": mode=0x" + Integer.toHexString(mode) + ", perms=0x" + Integer.toHexString(perms));
        }
        FilePermissionUtils.setPermissions(name, perms, -1, -1);
    }

    /**
     * @return
     */
    private final File getDataDirFile() {
        // ????????? getDir(Constant.LOCAL_PLUGIN_DATA_SUB_DIR)
        // ????????????????????????????????????files?????????????????????????????????getFilesDir + Constant.LOCAL_PLUGIN_DATA_SUB_DIR
        // File dir = getApplicationContext().getDir(Constant.LOCAL_PLUGIN_DATA_SUB_DIR, 0);

        // files
        // huchangqing getApplicationContext()?????????null
        File dir0 = getBaseContext().getFilesDir();

        // v3 data
        File dir = new File(dir0, Constant.LOCAL_PLUGIN_DATA_SUB_DIR);
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "can't create dir: " + dir.getAbsolutePath());
                }
                return null;
            }
            setFilePermissionsFromMode(dir.getPath(), 0, FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH);
        }

        // ?????????
        File file = makeFilename(dir, mPlugin);
        if (!file.exists()) {
            if (!file.mkdir()) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "can't create dir: " + file.getAbsolutePath());
                }
                return null;
            }
            setFilePermissionsFromMode(file.getPath(), 0, FilePermissionUtils.S_IRWXU | FilePermissionUtils.S_IRWXG | FilePermissionUtils.S_IXOTH);
        }

        return file;
    }

    private final View handleCreateView(String name, Context context, AttributeSet attrs) {
        // ????????????????????????
        if (mLoader.mIgnores.contains(name)) {
            // ?????????????????????????????????????????????????????????????????????
            if (LogDebug.LOG && RePlugin.getConfig().isPrintDetailLog()) {
                LogDebug.d(PLUGIN_TAG, "layout.cache: ignore plugin=" + mPlugin + " name=" + name);
            }
            return null;
        }

        // ???????????????
        Constructor<?> construct = mLoader.mConstructors.get(name);

        // ????????????
        if (construct == null) {
            // ??????
            Class<?> c = null;
            boolean found = false;
            do {
                try {
                    c = mNewClassLoader.loadClass(name);
                    if (c == null) {
                        // ??????????????????
                        break;
                    }
                    if (c == ViewStub.class) {
                        // ????????????????????????
                        break;
                    }
                    if (c.getClassLoader() != mNewClassLoader) {
                        // ????????????????????????
                        break;
                    }
                    // ??????
                    found = true;
                } catch (ClassNotFoundException e) {
                    // ???????????????
                    break;
                }
            } while (false);
            if (!found) {
                // ?????????????????????????????????????????????????????????????????????
                if (LogDebug.LOG && RePlugin.getConfig().isPrintDetailLog()) {
                    LogDebug.d(PLUGIN_TAG, "layout.cache: new ignore plugin=" + mPlugin + " name=" + name);
                }
                mLoader.mIgnores.add(name);
                return null;
            }
            // ????????????
            try {
                construct = c.getConstructor(Context.class, AttributeSet.class);
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "layout.cache: new constructor. plugin=" + mPlugin + " name=" + name);
                }
                mLoader.mConstructors.put(name, construct);
            } catch (Exception e) {
                InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating mobilesafe class " + name, e);
                throw ie;
            }
        }

        // ??????
        try {
            View v = (View) construct.newInstance(context, attrs);
            // ?????????????????????????????????????????????????????????????????????
            if (LogDebug.LOG && RePlugin.getConfig().isPrintDetailLog()) {
                LogDebug.d(PLUGIN_TAG, "layout.cache: create view ok. plugin=" + mPlugin + " name=" + name);
            }
            return v;
        } catch (Exception e) {
            InflateException ie = new InflateException(attrs.getPositionDescription() + ": Error inflating mobilesafe class " + name, e);
            throw ie;
        }
    }

    @Override
    public String getPackageName() {
        // NOTE ????????????????????????????????????????????????????????????PackageName
        // ?????????????????????????????????????????????????????????
        return super.getPackageName();
    }

    // --------------
    // WARNING ?????????
    // --------------
    // ???????????????????????????????????????Framework Ver??????????????????>=3??????????????????????????????????????????
    // Added by Jiongxuan Zhang
    @Override
    public Context getApplicationContext() {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3????????????????????????????????????PluginContext???????????????ApplicationContext
            return super.getApplicationContext();
        }
        // ?????????????????????Application??????
        // NOTE ????????????mLoader.mPkgContext???????????????????????????????????????getApplicationContext??????registerComponentCallback???
        // NOTE ???????????????StackOverflow???????????????????????????Application????????????????????????3??????????????????????????????
        //entry?????????context.getApplicationContext???mApplicationClient????????????????????????????????????????????????????????????
        if (mLoader.mPluginObj.mApplicationClient == null) {
            return this;
        } else {
            return mLoader.mPluginObj.mApplicationClient.getObj();
        }
    }


    @Override
    public void startActivity(Intent intent) {
        // HINT ????????????Application???????????????
        // ???Activity.startActivity??????????????????startActivityForResult??????????????????

        // ???????????????????????????
        // ????????????????????????????????????????????????????????????startActivity?????????????????????
        // ??????????????????????????????????????????Activity???????????????False????????????super?????????????????????????????????
        // ??????????????????????????????????????????????????????????????????????????????????????????Activity?????????????????????false??????super
        if (!Factory2.startActivity(this, intent)) {
            if (mContextInjector != null) {
                mContextInjector.startActivityBefore(intent);
            }

            super.startActivity(intent);

            if (mContextInjector != null) {
                mContextInjector.startActivityAfter(intent);
            }
        }
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        // HINT ???????????????startActivity?????????????????????
        // ?????????startActivity(intent)?????????????????????
        if (!Factory2.startActivity(this, intent)) {
            if (mContextInjector != null) {
                mContextInjector.startActivityBefore(intent, options);
            }

            super.startActivity(intent, options);

            if (mContextInjector != null) {
                mContextInjector.startActivityAfter(intent, options);
            }
        }
    }

    @Override
    public ComponentName startService(Intent service) {
        if (mContextInjector != null) {
            mContextInjector.startServiceBefore(service);
        }

        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            return super.startService(service);
        }
        try {
            return PluginServiceClient.startService(this, service, true);
        } catch (PluginClientHelper.ShouldCallSystem e) {
            // ????????????????????????????????????????????????
            return super.startService(service);
        } finally {
            if (mContextInjector != null) {
                mContextInjector.startServiceAfter(service);
            }
        }
    }

    @Override
    public boolean stopService(Intent name) {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            return super.stopService(name);
        }
        try {
            return PluginServiceClient.stopService(this, name, true);
        } catch (PluginClientHelper.ShouldCallSystem e) {
            // ????????????????????????????????????????????????
            return super.stopService(name);
        }
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            return super.bindService(service, conn, flags);
        }
        try {
            return PluginServiceClient.bindService(this, service, conn, flags, true);
        } catch (PluginClientHelper.ShouldCallSystem e) {
            // ????????????????????????????????????????????????
            return super.bindService(service, conn, flags);
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            super.unbindService(conn);
            return;
        }
        // ???????????????????????????
        try {
            super.unbindService(conn);
        } catch (Throwable e) {
            // Ignore
        }
        // ???????????????unbindService
        // NOTE ????????????????????????context.unbind???????????????????????????false
        PluginServiceClient.unbindService(this, conn, false);
    }

    @Override
    public String getPackageCodePath() {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            return super.getPackageCodePath();
        }
        // ????????????Apk?????????
        return mLoader.mPath;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        if (mLoader.mPluginObj.mInfo.getFrameworkVersion() <= 2) {
            // ??????????????????3?????????????????????
            return super.getApplicationInfo();
        }
        return mLoader.mComponents.getApplication();
    }
}
