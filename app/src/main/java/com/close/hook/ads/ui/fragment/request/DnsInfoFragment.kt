package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentDnsInfoBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment

class DnsInfoFragment : BaseFragment<FragmentDnsInfoBinding>() {

    companion object {
        private const val DNS_HOST = "dnsHost"
        private const val FULL_ADDRESS = "fullAddress"

        fun newInstance(dnsHost: String, fullAddress: String?): DnsInfoFragment {
            return DnsInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(DNS_HOST, dnsHost)
                    putString(FULL_ADDRESS, fullAddress)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dnsHost = arguments?.getString(DNS_HOST) ?: ""
        val fullAddress = arguments?.getString(FULL_ADDRESS) ?: ""

        binding.dnsHostText.text = dnsHost
        binding.fullAddressText.text = fullAddress
    }
}
