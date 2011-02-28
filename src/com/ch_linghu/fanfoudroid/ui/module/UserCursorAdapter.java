/**
 * 
 */
package com.ch_linghu.fanfoudroid.ui.module;

import java.text.ParseException;
import java.util.Date;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.ch_linghu.fanfoudroid.R;
import com.ch_linghu.fanfoudroid.TwitterApplication;
import com.ch_linghu.fanfoudroid.data.Tweet;
import com.ch_linghu.fanfoudroid.data.db.StatusDatabase;
import com.ch_linghu.fanfoudroid.data.db.StatusTablesInfo.StatusTable;
import com.ch_linghu.fanfoudroid.helper.Preferences;
import com.ch_linghu.fanfoudroid.helper.ProfileImageCacheCallback;
import com.ch_linghu.fanfoudroid.helper.Utils;

public class UserCursorAdapter extends CursorAdapter implements TweetAdapter {
	private static final String TAG = "TweetCursorAdapter";
	
	private Context mContext;

	public UserCursorAdapter(Context context, Cursor cursor) {
		super(context, cursor);
		mContext = context;

		if (context != null) {
			mInflater = LayoutInflater.from(context);
		}

		if (cursor != null) {
		    //TODO: 可使用:
		    //Tweet tweet = StatusTable.parseCursor(cursor);
		    
			mUserTextColumn = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_USER_SCREEN_NAME);
			mTextColumn = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_TEXT);
			mProfileImageUrlColumn = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_PROFILE_IMAGE_URL);
			mCreatedAtColumn = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_CREATED_AT);
			mSourceColumn = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_SOURCE);
			mInReplyToScreenName = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_IN_REPLY_TO_SCREEN_NAME);
			mFavorited = cursor
					.getColumnIndexOrThrow(StatusTable.FIELD_FAVORITED);
		}
		mMetaBuilder = new StringBuilder();
	}

	private LayoutInflater mInflater;

	private int mUserTextColumn;
	private int mTextColumn;
	private int mProfileImageUrlColumn;
	private int mCreatedAtColumn;
	private int mSourceColumn;
	private int mInReplyToScreenName;
	private int mFavorited;

	private StringBuilder mMetaBuilder;
	
	private ProfileImageCacheCallback callback = new ProfileImageCacheCallback(){

		@Override
		public void refresh(String url, Bitmap bitmap) {
			UserCursorAdapter.this.refresh();
		}
		
	};

	@Override
	public View newView(Context context, Cursor cursor, ViewGroup parent) {
		View view = mInflater.inflate(R.layout.tweet, parent, false);

		UserCursorAdapter.ViewHolder holder = new ViewHolder();
		holder.tweetUserText = (TextView) view
				.findViewById(R.id.tweet_user_text);
		holder.tweetText = (TextView) view.findViewById(R.id.tweet_text);
		holder.profileImage = (ImageView) view.findViewById(R.id.profile_image);
		holder.metaText = (TextView) view.findViewById(R.id.tweet_meta_text);
		holder.fav = (ImageView) view.findViewById(R.id.tweet_fav);
		view.setTag(holder);

		return view;
	}

	private static class ViewHolder {
		public TextView tweetUserText;
		public TextView tweetText;
		public ImageView profileImage;
		public TextView metaText;
		public ImageView fav;
	}

	@Override
	public void bindView(View view, Context context, Cursor cursor) {
		UserCursorAdapter.ViewHolder holder = (UserCursorAdapter.ViewHolder) view
				.getTag();
		
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(mContext);;
		boolean useProfileImage = pref.getBoolean(Preferences.USE_PROFILE_IMAGE, true);
		holder.tweetUserText.setText(cursor.getString(mUserTextColumn));
		Utils.setSimpleTweetText(holder.tweetText, cursor.getString(mTextColumn));

		String profileImageUrl = cursor.getString(mProfileImageUrlColumn);

		if (useProfileImage){
			if (!Utils.isEmpty(profileImageUrl)) {
				holder.profileImage.setImageBitmap(TwitterApplication.mProfileImageCacheManager
						.get(profileImageUrl, callback));
			}
		}else{
			holder.profileImage.setVisibility(View.GONE);
		}
		
		if (cursor.getString(mFavorited).equals("true")) {
			holder.fav.setVisibility(View.VISIBLE);
		} else {
			holder.fav.setVisibility(View.INVISIBLE);
		}

		try {
			Date createdAt = StatusDatabase.DB_DATE_FORMATTER.parse(cursor
					.getString(mCreatedAtColumn));
			holder.metaText.setText(Tweet.buildMetaText(mMetaBuilder,
					createdAt, cursor.getString(mSourceColumn), cursor
							.getString(mInReplyToScreenName)));
		} catch (ParseException e) {
			Log.w(TAG, "Invalid created at data.");
		}
	}

	@Override
	public void refresh() {
		getCursor().requery();
	}
}