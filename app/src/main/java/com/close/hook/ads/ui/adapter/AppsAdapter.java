package com.close.hook.ads.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import com.close.hook.ads.R;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.databinding.InstallsItemAppBinding;

import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {

    private final AsyncListDiffer<AppInfo> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private final PublishSubject<AppInfo> onClickSubject = PublishSubject.create();

    private static final DiffUtil.ItemCallback<AppInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<AppInfo>() {
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
        InstallsItemAppBinding binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new AppViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo appInfo = differ.getCurrentList().get(position);
        holder.bind(appInfo);
        holder.binding.getRoot().setOnClickListener(v -> onClickSubject.onNext(appInfo));
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        final InstallsItemAppBinding binding;

        AppViewHolder(InstallsItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(AppInfo appInfo) {
            binding.textViewAppName.setText(appInfo.getAppName());
            binding.textViewAppVersion.setText(appInfo.getVersionName());

            Glide.with(binding.imageViewAppIcon.getContext())
                .load(appInfo.getAppIcon())
                .apply(new RequestOptions()
                    .override(binding.imageViewAppIcon.getContext().getResources().getDimensionPixelSize(R.dimen.app_icon_size))
                    .diskCacheStrategy(DiskCacheStrategy.ALL))
                .into(binding.imageViewAppIcon);
        }
    }
}
