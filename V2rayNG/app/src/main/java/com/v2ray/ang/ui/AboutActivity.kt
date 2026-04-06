package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.handler.V2RayNativeManager

class AboutActivity : BaseActivity() {
    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))

        "v${BuildConfig.VERSION_NAME}".also {
            binding.tvVersion.text = it
        }
        binding.tvAppId.text = "True VPN"
    }
}
