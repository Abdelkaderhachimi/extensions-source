<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://android.com"
    package="eu.kanade.tachiyomi.extension.ar.mangapro">

    <!-- السماح للإضافة بالاتصال بالإنترنت -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="Tachiyomi: ProComic (Custom)">

        <!-- تعريف الـ UrlActivity لفتح الروابط من المتصفح مباشرة -->
        <activity
            android:name=".UrlActivity"
            android:exported="true"
            android:label="ProComic Path Intent"
            android:theme="@android:style/Theme.NoDisplay">
            
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                
                <!-- بروتوكول الموقع -->
                <data android:scheme="http" />
                <data android:scheme="https" />
                
                <!-- رابط الموقع الرئيسي -->
                <data android:host="procomic.pro" />
                
                <!-- مسارات الروابط التي سيتم اعتراضها (مانهوا، فصل) -->
                <data android:pathPrefix="/series/" />
                <data android:pathPrefix="/manga/" />
            </intent-filter>
        </activity>

        <!-- تعريف الفئة الرئيسية للإضافة (Metadata) ليتعرف عليها تاتشيومي -->
        <meta-data
            android:name="tachiyomi.extension.class"
            android:value=".ProComic" />
        
        <meta-data
            android:name="tachiyomi.extension.version"
            android:value="1" />
            
        <meta-data
            android:name="tachiyomi.extension.nsfw"
            android:value="0" /> <!-- 0 للمحتوى العام، 1 للمحتوى البالغين -->

    </application>
</manifest>
