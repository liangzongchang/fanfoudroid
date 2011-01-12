package com.ch_linghu.fanfoudroid;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.ch_linghu.fanfoudroid.data.Dm;
import com.ch_linghu.fanfoudroid.data.db.TwitterDbAdapter;
import com.ch_linghu.fanfoudroid.helper.ImageManager;
import com.ch_linghu.fanfoudroid.helper.Preferences;
import com.ch_linghu.fanfoudroid.helper.Utils;
import com.ch_linghu.fanfoudroid.ui.base.WithHeaderActivity;
import com.ch_linghu.fanfoudroid.weibo.DirectMessage;
import com.ch_linghu.fanfoudroid.weibo.Paging;
import com.ch_linghu.fanfoudroid.weibo.WeiboException;
import com.google.android.photostream.UserTask;

public class DmActivity extends WithHeaderActivity {

  private static final String TAG = "DmActivity";

  // Views.
  private ListView mTweetList;
  private Adapter mAdapter;
  
  private TextView mProgressText;

  // Tasks.
  private UserTask<Void, Void, TaskResult> mRetrieveTask;
  private UserTask<String, Void, TaskResult> mDeleteTask;

  // Refresh data at startup if last refresh was this long ago or greater.
  private static final long REFRESH_THRESHOLD = 5 * 60 * 1000;

  private static final String EXTRA_USER = "user";

  private static final String LAUNCH_ACTION = "com.ch_linghu.fanfoudroid.DMS";

  public static Intent createIntent() {
    return createIntent("");
  }

  public static Intent createIntent(String user) {
    Intent intent = new Intent(LAUNCH_ACTION);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

    if (!Utils.isEmpty(user)) {
      intent.putExtra(EXTRA_USER, user);
    }

    return intent;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!getApi().isLoggedIn()) {
      Log.i(TAG, "Not logged in.");
      handleLoggedOut();
      return;
    }

    setContentView(R.layout.dm);
    initHeader(HEADER_STYLE_HOME);
    setHeaderTitle("我的私信");
    
    // 绑定底部栏按钮onClick监听器
    bindFooterButtonEvent();

    mTweetList = (ListView) findViewById(R.id.tweet_list);

    mProgressText = (TextView) findViewById(R.id.progress_text);

    TwitterDbAdapter db = getDb();
    // Mark all as read.
    db.markAllDmsRead();

    setupAdapter();

    boolean shouldRetrieve = false;

    long lastRefreshTime = mPreferences.getLong(
        Preferences.LAST_DM_REFRESH_KEY, 0);
    long nowTime = Utils.getNowTime();

    long diff = nowTime - lastRefreshTime;
    Log.i(TAG, "Last refresh was " + diff + " ms ago.");

    if (diff > REFRESH_THRESHOLD) {
      shouldRetrieve = true;
    } else if (Utils.isTrue(savedInstanceState, SIS_RUNNING_KEY)) {
      // Check to see if it was running a send or retrieve task.
      // It makes no sense to resend the send request (don't want dupes)
      // so we instead retrieve (refresh) to see if the message has posted.
      Log.i(TAG, "Was last running a retrieve or send task. Let's refresh.");
      shouldRetrieve = true;
    }

    if (shouldRetrieve) {
      doRetrieve();
    }

    // Want to be able to focus on the items with the trackball.
    // That way, we can navigate up and down by changing item focus.
    mTweetList.setItemsCanFocus(true);
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (!getApi().isLoggedIn()) {
      Log.i(TAG, "Not logged in.");
      handleLoggedOut();
      return;
    }
  }

  private static final String SIS_RUNNING_KEY = "running";

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    if (mRetrieveTask != null
        && mRetrieveTask.getStatus() == UserTask.Status.RUNNING) {
      outState.putBoolean(SIS_RUNNING_KEY, true);
    } 
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy.");
   
    if (mDeleteTask != null
        && mDeleteTask.getStatus() == UserTask.Status.RUNNING) {
      mDeleteTask.cancel(true);
    }

    super.onDestroy();
  }

  // UI helpers.
  
  private void bindFooterButtonEvent() {
	  // TODO: 绑定私信收信箱/发信箱切换按钮的监听器
	  Button inbox   = (Button) findViewById(R.id.inbox);
	  Button sendbox = (Button) findViewById(R.id.sendbox);
	  Button newMsg  = (Button) findViewById(R.id.new_message);
	  
	  newMsg.setOnClickListener(new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			Intent intent = new Intent();
			intent.setClass(DmActivity.this, WriteDmActivity.class);
			intent.putExtra("reply_to_id", 0); //TODO: 传入实际的reply_to_id
			startActivity(intent);
		}
	  });
  }

  private void updateProgress(String progress) {
    mProgressText.setText(progress);
  }

  private void setupAdapter() {
    Cursor cursor = getDb().fetchAllDms();
    startManagingCursor(cursor);

    mAdapter = new Adapter(this, cursor);
    mTweetList.setAdapter(mAdapter);
    registerForContextMenu(mTweetList);
  }

  private void draw() {
    mAdapter.refresh();
  }

  private void goTop() {
    mTweetList.setSelection(0);
  }

  private void doRetrieve() {
    Log.i(TAG, "Attempting retrieve.");
    
    // 旋转刷新按钮
	animRotate(refreshButton);

    if (mRetrieveTask != null
        && mRetrieveTask.getStatus() == UserTask.Status.RUNNING) {
      Log.w(TAG, "Already retrieving.");
    } else {
      mRetrieveTask = new RetrieveTask().execute();
    }
  }

  private void onRetrieveBegin() {
    updateProgress("Refreshing...");
  }
  
  private enum TaskResult {
	    OK, IO_ERROR, AUTH_ERROR, CANCELLED, NOT_FOLLOWED_ERROR
  }

  private class RetrieveTask extends UserTask<Void, Void, TaskResult> {
    @Override
    public void onPreExecute() {
      onRetrieveBegin();
    }

    @Override
    public void onProgressUpdate(Void... progress) {
      draw();
    }

    @Override
    public TaskResult doInBackground(Void... params) {
      List<DirectMessage> dmList;

      ArrayList<Dm> dms = new ArrayList<Dm>();

      TwitterDbAdapter db = getDb();
      ImageManager imageManager = getImageManager();

      String maxId = db.fetchMaxDmId(false);

      HashSet<String> imageUrls = new HashSet<String>();

      try {
    	  Paging paging = new Paging(maxId);
    	  dmList = getApi().getDirectMessages(paging);
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      for (DirectMessage directMessage : dmList) {
        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }

        Dm dm;

	    dm = Dm.create(directMessage, false);
        dms.add(dm);
        imageUrls.add(dm.profileImageUrl);

        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }
      }

      maxId = db.fetchMaxDmId(true);

      try {
    	  Paging paging = new Paging(maxId);
    	  dmList = getApi().getSentDirectMessages(paging);
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      for (DirectMessage directMessage : dmList) {
        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }

        Dm dm;

        dm = Dm.create(directMessage, true);
        dms.add(dm);
        imageUrls.add(dm.profileImageUrl);

        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }
      }

      db.addDms(dms, false);

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      publishProgress();

      for (String imageUrl : imageUrls) {
        if (!Utils.isEmpty(imageUrl)) {
          // Fetch image to cache.
          try {
            imageManager.put(imageUrl);
          } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
          }
        }

        if (isCancelled()) {
          return TaskResult.CANCELLED;
        }
      }

      return TaskResult.OK;
    }

    @Override
    public void onPostExecute(TaskResult result) {
      if (result == TaskResult.AUTH_ERROR) {
        logout();
      } else if (result == TaskResult.OK) {
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putLong(Preferences.LAST_DM_REFRESH_KEY, Utils.getNowTime());
        editor.commit();
        draw();
        goTop();
      } else {
        // Do nothing.
      }

      // 刷新按钮停止旋转
	  getRefreshButton().clearAnimation();
      updateProgress("");
    }
  }

  private static class Adapter extends CursorAdapter {

    public Adapter(Context context, Cursor cursor) {
      super(context, cursor);

      mInflater = LayoutInflater.from(context);

      mUserTextColumn = cursor.getColumnIndexOrThrow(TwitterDbAdapter.KEY_USER);
      mTextColumn = cursor.getColumnIndexOrThrow(TwitterDbAdapter.KEY_TEXT);
      mProfileImageUrlColumn = cursor
          .getColumnIndexOrThrow(TwitterDbAdapter.KEY_PROFILE_IMAGE_URL);
      mCreatedAtColumn = cursor
          .getColumnIndexOrThrow(TwitterDbAdapter.KEY_CREATED_AT);
      mIsSentColumn = cursor
          .getColumnIndexOrThrow(TwitterDbAdapter.KEY_IS_SENT);
    }

    private LayoutInflater mInflater;

    private int mUserTextColumn;
    private int mTextColumn;
    private int mProfileImageUrlColumn;
    private int mIsSentColumn;
    private int mCreatedAtColumn;

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
      View view = mInflater.inflate(R.layout.direct_message, parent, false);

      ViewHolder holder = new ViewHolder();
      holder.userText = (TextView) view.findViewById(R.id.tweet_user_text);
      holder.tweetText = (TextView) view.findViewById(R.id.tweet_text);
      holder.profileImage = (ImageView) view.findViewById(R.id.profile_image);
      holder.metaText = (TextView) view.findViewById(R.id.tweet_meta_text);
      view.setTag(holder);

      return view;
    }

    class ViewHolder {
      public TextView userText;
      public TextView tweetText;
      public ImageView profileImage;
      public TextView metaText;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
      ViewHolder holder = (ViewHolder) view.getTag();

      int isSent = cursor.getInt(mIsSentColumn);
      String user = cursor.getString(mUserTextColumn);
   	  
      if (0 == isSent) {
          holder.userText.setText("From: " + user);
      } else {
    	  holder.userText.setText("To: " + user);
      }

      Utils.setTweetText(holder.tweetText, cursor.getString(mTextColumn));

      String profileImageUrl = cursor.getString(mProfileImageUrlColumn);

      if (!Utils.isEmpty(profileImageUrl)) {
        holder.profileImage.setImageBitmap(TwitterApplication.mImageManager.get(profileImageUrl));
      }

      try {
        holder.metaText.setText(Utils
            .getRelativeDate(TwitterDbAdapter.DB_DATE_FORMATTER.parse(cursor
                .getString(mCreatedAtColumn))));
      } catch (ParseException e) {
        Log.w(TAG, "Invalid created at data.");
      }
    }

    public void refresh() {
      getCursor().requery();
    }

  }

  
  // Menu.

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuItem item = menu.add(0, OPTIONS_MENU_ID_REFRESH, 0, R.string.refresh);
    item.setIcon(R.drawable.refresh);

    item = menu.add(0, OPTIONS_MENU_ID_TWEETS, 0, R.string.tweets);
    item.setIcon(android.R.drawable.ic_menu_view);

    item = menu.add(0, OPTIONS_MENU_ID_REPLIES, 0,
            R.string.show_at_replies);
        item.setIcon(android.R.drawable.ic_menu_revert);
        

    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
    case OPTIONS_MENU_ID_REFRESH:
      doRetrieve();
      return true;
    case OPTIONS_MENU_ID_TWEETS:
      launchActivity(TwitterActivity.createIntent(this));
      return true;
    case OPTIONS_MENU_ID_REPLIES:
        launchActivity(MentionActivity.createIntent(this));
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private static final int CONTEXT_REPLY_ID = 0;
  private static final int CONTEXT_DELETE_ID = 1;

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v,
      ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    menu.add(0, CONTEXT_REPLY_ID, 0, R.string.reply);
    menu.add(0, CONTEXT_DELETE_ID, 0, R.string.delete);
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    Cursor cursor = (Cursor) mAdapter.getItem(info.position);

    if (cursor == null) {
      Log.w(TAG, "Selected item not available.");
      return super.onContextItemSelected(item);
    }

    switch (item.getItemId()) {
    case CONTEXT_REPLY_ID:
      String user_id = cursor.getString(cursor
          .getColumnIndexOrThrow(TwitterDbAdapter.KEY_USER_ID));
      Intent intent = WriteDmActivity.createIntent(user_id);
      startActivity(intent);
      
      return true;
    case CONTEXT_DELETE_ID:
      int idIndex = cursor.getColumnIndexOrThrow(TwitterDbAdapter.KEY_ID);
      String id = cursor.getString(idIndex);
      doDestroy(id);

      return true;
    default:
      return super.onContextItemSelected(item);
    }
  }

  private void doDestroy(String id) {
    Log.i(TAG, "Attempting delete.");

    if (mDeleteTask != null
        && mDeleteTask.getStatus() == UserTask.Status.RUNNING) {
      Log.w(TAG, "Already deleting.");
    } else {
      mDeleteTask = new DeleteTask().execute(new String[] { id });
    }
  }

  private class DeleteTask extends UserTask<String, Void, TaskResult> {
    @Override
    public void onPreExecute() {
      updateProgress("Deleting...");
    }

    @Override
    public TaskResult doInBackground(String... params) {
      String id = params[0];

      try {
        DirectMessage directMessage = getApi().destroyDirectMessage(id);
        Dm.create(directMessage, false);
        getDb().deleteDm(id);
      } catch (WeiboException e) {
        Log.e(TAG, e.getMessage(), e);
        return TaskResult.IO_ERROR;
      }

      if (isCancelled()) {
        return TaskResult.CANCELLED;
      }

      return TaskResult.OK;
    }

    @Override
    public void onPostExecute(TaskResult result) {
      if (result == TaskResult.AUTH_ERROR) {
        logout();
      } else if (result == TaskResult.OK) {
        mAdapter.refresh();
      } else {
        // Do nothing.
      }

      updateProgress("");
    }
  }

   //for Retrievable interface
   public ImageButton getRefreshButton() {
		return refreshButton;
   }

}