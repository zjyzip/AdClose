package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentDnsInfoBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment

class DnsInfoFragment : BaseFragment<FragmentDnsInfoBinding>() {

    companion object {
        private const val DNS_HOST = "dnsHost"
        private const val DNS_CIDR = "dnsCidr"
        private const val FULL_ADDRESS = "fullAddress"

        fun newInstance(dnsHost: String, dnsCidr: String, fullAddress: String?): DnsInfoFragment {
            return DnsInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(DNS_HOST, dnsHost)
                    putString(DNS_CIDR, dnsCidr)
                    putString(FULL_ADDRESS, fullAddress)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dnsHost = arguments?.getString(DNS_HOST) ?: ""
        val dnsCidr = arguments?.getString(DNS_CIDR) ?: ""
        val fullAddress = arguments?.getString(FULL_ADDRESS) ?: ""

        binding.dnsHostText.text = dnsHost
        binding.dnsCidrText.text = dnsCidr
        binding.fullAddressText.text = fullAddress
    }
}
