package com.close.hook.ads.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.close.hook.ads.R;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.databinding.InstallsItemAppBinding;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class AppsAdapter extends ListAdapter<AppInfo, AppsAdapter.AppViewHolder> {

    private final PublishSubject<String> onClickSubject = PublishSubject.create();
    private final PublishSubject<String> onLongClickSubject = PublishSubject.create();

    private static final DiffUtil.ItemCallback<AppInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<AppInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            return oldItem.getPackageName().equals(newItem.getPackageName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            boolean appNameSame = oldItem.getAppName() != null ? oldItem.getAppName().equals(newItem.getAppName()) : newItem.getAppName() == null;
            boolean versionNameSame = oldItem.getVersionName() != null ? oldItem.getVersionName().equals(newItem.getVersionName()) : newItem.getVersionName() == null;
            boolean versionCodeSame = oldItem.getVersionCode() == newItem.getVersionCode();

            return appNameSame && versionNameSame && versionCodeSame;
        }
    };

    public AppsAdapter() {
        super(DIFF_CALLBACK);
    }

    public Observable<String> getOnClickObservable() {
        return onClickSubject;
    }

    public Observable<String> getOnLongClickObservable() {
        return onLongClickSubject;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        InstallsItemAppBinding binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new AppViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo appInfo = getItem(position);
        holder.bind(appInfo);

        holder.binding.getRoot().setOnClickListener(v -> onClickSubject.onNext(appInfo.getPackageName()));
        holder.binding.getRoot().setOnLongClickListener(v -> {
            onLongClickSubject.onNext(appInfo.getPackageName());
            return true;
        });
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        final InstallsItemAppBinding binding;

        AppViewHolder(InstallsItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(AppInfo appInfo) {
            binding.appName.setText(appInfo.getAppName());
            binding.packageName.setText(appInfo.getPackageName());
            binding.appVersion.setText(appInfo.getVersionName() + " (" + appInfo.getVersionCode() + ")");
            if (appInfo.getAppIcon() != null) {
                Glide.with(binding.appIcon.getContext())
                        .load(appInfo.getAppIcon())
                        .apply(new RequestOptions()
                                .override(binding.appIcon.getContext().getResources().getDimensionPixelSize(R.dimen.app_icon_size))
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .into(binding.appIcon);
            } else {
                binding.appIcon.setImageDrawable(null);
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull AppViewHolder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.binding.appIcon.getContext()).clear(holder.binding.appIcon);
    }
}
