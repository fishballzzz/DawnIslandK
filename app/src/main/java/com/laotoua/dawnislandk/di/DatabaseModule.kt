package com.laotoua.dawnislandk.di

import android.content.Context
import androidx.room.Room
import com.laotoua.dawnislandk.data.local.dao.CommunityDao
import com.laotoua.dawnislandk.data.local.dao.CookieDao
import com.laotoua.dawnislandk.data.local.dao.DawnDatabase
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDawnDB(applicationContext: Context): DawnDatabase {
        return Room.databaseBuilder(
            applicationContext,
            DawnDatabase::class.java, "dawnDB"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideCommunityDao(dawnDatabase: DawnDatabase): CommunityDao {
        return dawnDatabase.communityDao()
    }

    @Provides
    @Singleton
    fun provideCookieDao(dawnDatabase: DawnDatabase): CookieDao {
        return dawnDatabase.cookieDao()
    }
}