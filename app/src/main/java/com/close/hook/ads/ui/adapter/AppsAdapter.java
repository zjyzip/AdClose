package com.close.hook.ads.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.close.hook.ads.R;
import com.close.hook.ads.data.model.AppInfo;

import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {

  private final AsyncListDiffer<AppInfo> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
  private final PublishSubject<AppInfo> onClickSubject = PublishSubject.create();

  private static final DiffUtil.ItemCallback<AppInfo> DIFF_CALLBACK =
      new DiffUtil.ItemCallback<AppInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
          return oldItem.getPackageName().equals(newItem.getPackageName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
          return oldItem.equals(newItem);
        }
      };

  public void submitList(List<AppInfo> list) {
    differ.submitList(list);
  }

  public Observable<AppInfo> getOnClickObservable() {
    return onClickSubject;
  }

  @NonNull
  @Override
  public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View itemView =
        LayoutInflater.from(parent.getContext()).inflate(R.layout.installs_item_app, parent, false);
    return new AppViewHolder(itemView);
  }

  @Override
  public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
    AppInfo appInfo = differ.getCurrentList().get(position);
    holder.bind(appInfo);
    holder.itemView.setOnClickListener(v -> onClickSubject.onNext(appInfo));
  }

  @Override
  public int getItemCount() {
    return differ.getCurrentList().size();
  }

  static class AppViewHolder extends RecyclerView.ViewHolder {
    TextView appNameTextView;
    ImageView appIconImageView;
    TextView appVersionTextView;

    AppViewHolder(View itemView) {
      super(itemView);
      appNameTextView = itemView.findViewById(R.id.text_view_app_name);
      appIconImageView = itemView.findViewById(R.id.image_view_app_icon);
      appVersionTextView = itemView.findViewById(R.id.text_view_app_version);
    }

    void bind(AppInfo appInfo) {
      appNameTextView.setText(appInfo.getAppName());
      appVersionTextView.setText(appInfo.getVersionName());

      Glide.with(itemView.getContext())
          .load(appInfo.getAppIcon())
          .apply(new RequestOptions().override(150, 150).diskCacheStrategy(DiskCacheStrategy.ALL))
          .into(appIconImageView);
    }
  }
}
