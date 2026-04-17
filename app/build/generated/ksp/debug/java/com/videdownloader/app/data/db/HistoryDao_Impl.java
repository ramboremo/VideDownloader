package com.videdownloader.app.data.db;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class HistoryDao_Impl implements HistoryDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<HistoryEntity> __insertionAdapterOfHistoryEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteHistoryById;

  private final SharedSQLiteStatement __preparedStmtOfDeleteHistoryBefore;

  private final SharedSQLiteStatement __preparedStmtOfDeleteHistorySince;

  private final SharedSQLiteStatement __preparedStmtOfClearHistory;

  public HistoryDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfHistoryEntity = new EntityInsertionAdapter<HistoryEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `history` (`id`,`url`,`title`,`favicon`,`visitedAt`) VALUES (nullif(?, 0),?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final HistoryEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getUrl());
        statement.bindString(3, entity.getTitle());
        if (entity.getFavicon() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getFavicon());
        }
        statement.bindLong(5, entity.getVisitedAt());
      }
    };
    this.__preparedStmtOfDeleteHistoryById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM history WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteHistoryBefore = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM history WHERE visitedAt < ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteHistorySince = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM history WHERE visitedAt >= ?";
        return _query;
      }
    };
    this.__preparedStmtOfClearHistory = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM history";
        return _query;
      }
    };
  }

  @Override
  public Object insertHistory(final HistoryEntity history,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfHistoryEntity.insert(history);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteHistoryById(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteHistoryById.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteHistoryById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteHistoryBefore(final long timestamp,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteHistoryBefore.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, timestamp);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteHistoryBefore.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteHistorySince(final long timestamp,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteHistorySince.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, timestamp);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteHistorySince.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearHistory(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearHistory.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearHistory.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<HistoryEntity>> getAllHistory() {
    final String _sql = "SELECT * FROM history ORDER BY visitedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"history"}, new Callable<List<HistoryEntity>>() {
      @Override
      @NonNull
      public List<HistoryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfVisitedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "visitedAt");
          final List<HistoryEntity> _result = new ArrayList<HistoryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final HistoryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpUrl;
            _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpVisitedAt;
            _tmpVisitedAt = _cursor.getLong(_cursorIndexOfVisitedAt);
            _item = new HistoryEntity(_tmpId,_tmpUrl,_tmpTitle,_tmpFavicon,_tmpVisitedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<HistoryEntity>> searchHistory(final String query) {
    final String _sql = "SELECT * FROM history WHERE url LIKE '%' || ? || '%' OR title LIKE '%' || ? || '%' ORDER BY visitedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, query);
    _argIndex = 2;
    _statement.bindString(_argIndex, query);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"history"}, new Callable<List<HistoryEntity>>() {
      @Override
      @NonNull
      public List<HistoryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfVisitedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "visitedAt");
          final List<HistoryEntity> _result = new ArrayList<HistoryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final HistoryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpUrl;
            _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpVisitedAt;
            _tmpVisitedAt = _cursor.getLong(_cursorIndexOfVisitedAt);
            _item = new HistoryEntity(_tmpId,_tmpUrl,_tmpTitle,_tmpFavicon,_tmpVisitedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
