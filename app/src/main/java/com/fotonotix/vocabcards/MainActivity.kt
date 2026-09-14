package com.fotonotix.vocabcards

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.fotonotix.vocabcards.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0    -> ClipboardFragment()
                else -> StudyFragment()
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) { 0 -> "Clipboard"; else -> "Study" }
        }.attach()
    }
}
