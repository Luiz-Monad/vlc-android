package org.videolan.vlc.gui.helpers

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.widget.ViewStubCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.BrowseFrameLayout.OnFocusSearchListener
import androidx.lifecycle.LifecycleObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.resources.AndroidDevices
import org.videolan.resources.R as RR
import org.videolan.resources.VLCOptions
import org.videolan.tools.AppScope
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.databinding.PlayerOptionItemBinding
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.BaseActivity
import org.videolan.vlc.gui.DiffUtilAdapter
import org.videolan.vlc.gui.audio.EqualizerFragment
import org.videolan.vlc.gui.dialogs.*
import org.videolan.vlc.gui.helpers.UiTools.addToPlaylist
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.media.PlayerController
import org.videolan.vlc.util.getScreenHeight
import org.videolan.vlc.util.isTalkbackIsEnabled

private const val ACTION_AUDIO_DELAY = 2
private const val ACTION_SPU_DELAY = 3

private const val ID_PLAY_AS_AUDIO = 0L
private const val ID_SLEEP = 1L
private const val ID_JUMP_TO = 2L
private const val ID_PLAY_AS_VIDEO = 3L
private const val ID_BOOKMARK = 4L
private const val ID_CHAPTER_TITLE = 5L
private const val ID_PLAYBACK_SPEED = 6L
private const val ID_EQUALIZER = 7L
private const val ID_SAVE_PLAYLIST = 8L
private const val ID_POPUP_VIDEO = 9L
private const val ID_REPEAT = 10L
private const val ID_SHUFFLE = 11L
private const val ID_PASSTHROUGH = 12L
private const val ID_ABREPEAT = 13L
private const val ID_LOCK_PLAYER = 14L
private const val ID_VIDEO_STATS = 15L
private const val ID_SHOW_VIDEO_TIPS = 16L
private const val ID_SHOW_AUDIO_TIPS = 17L
private const val ID_SHOW_PLAYLIST_TIPS = 18L
private const val ID_VIDEO_CONTROLS_SETTING = 19L
private const val ID_AUDIO_CONTROLS_SETTING = 20L
@SuppressLint("ShowToast")
class PlayerOptionsDelegate(val activity: FragmentActivity, val service: PlaybackService, private val showABReapeat:Boolean = true) : LifecycleObserver {

    private lateinit var bookmarkClickedListener: () -> Unit
    private lateinit var recyclerview: RecyclerView
    private lateinit var rootView: FrameLayout
    var flags: Long = 0L
    private val toast by lazy(LazyThreadSafetyMode.NONE) { Toast.makeText(activity, "", Toast.LENGTH_SHORT) }

    private val primary = activity is VideoPlayerActivity && activity.displayManager.isPrimary
    private val isChromecast = activity is VideoPlayerActivity && activity.displayManager.isOnRenderer
    private val video = activity is VideoPlayerActivity
    private val res = activity.resources
    private val settings = Settings.getInstance(activity)
    private lateinit var abrBinding: PlayerOptionItemBinding
    private lateinit var ptBinding: PlayerOptionItemBinding
    private lateinit var repeatBinding: PlayerOptionItemBinding
    private lateinit var shuffleBinding: PlayerOptionItemBinding
    private lateinit var sleepBinding: PlayerOptionItemBinding

    fun setup() {
        if (!this::recyclerview.isInitialized || PlayerController.playbackState == PlaybackStateCompat.STATE_STOPPED) return
        val options = mutableListOf<PlayerOption>()
        if (video) options.add(PlayerOption(ID_LOCK_PLAYER, RR.drawable.ic_lock_player, res.getString(RR.string.lock)))
        options.add(PlayerOption(ID_SLEEP, RR.drawable.ic_sleep, res.getString(RR.string.sleep_title)))
        if (!isChromecast) options.add(PlayerOption(ID_PLAYBACK_SPEED, RR.drawable.ic_speed, res.getString(RR.string.playback_speed)))
        options.add(PlayerOption(ID_JUMP_TO, RR.drawable.ic_jumpto, res.getString(RR.string.jump_to_time)))
        options.add(PlayerOption(ID_EQUALIZER, RR.drawable.ic_equalizer, res.getString(RR.string.equalizer)))
        if (video) {
            if (primary && !Settings.showTvUi && service.audioTracksCount > 0)
                options.add(PlayerOption(ID_PLAY_AS_AUDIO, RR.drawable.ic_playasaudio_on, res.getString(RR.string.play_as_audio)))
            if (primary && AndroidDevices.pipAllowed && !AndroidDevices.isDex(activity))
                options.add(PlayerOption(ID_POPUP_VIDEO, RR.drawable.ic_popup_dim, res.getString(RR.string.ctx_pip_title)))
            if (primary)
                options.add(PlayerOption(ID_REPEAT, RR.drawable.ic_repeat, res.getString(RR.string.repeat_title)))
            if (service.canShuffle()) options.add(PlayerOption(ID_SHUFFLE, RR.drawable.ic_shuffle, res.getString(RR.string.shuffle_title)))
            options.add(PlayerOption(ID_VIDEO_STATS, RR.drawable.ic_video_stats, res.getString(RR.string.video_information)))
        } else {
            if (service.videoTracksCount > 0) options.add(PlayerOption(ID_PLAY_AS_VIDEO, RR.drawable.ic_playasaudio_off, res.getString(RR.string.play_as_video)))
        }
        val chaptersCount = service.getChapters(-1)?.size ?: 0
        if (chaptersCount > 1) options.add(PlayerOption(ID_CHAPTER_TITLE, RR.drawable.ic_chapter, res.getString(RR.string.go_to_chapter)))
        if (::bookmarkClickedListener.isInitialized) options.add(PlayerOption(ID_BOOKMARK, RR.drawable.ic_bookmark, res.getString(RR.string.bookmarks)))
        if (showABReapeat) options.add(PlayerOption(ID_ABREPEAT, RR.drawable.ic_abrepeat, res.getString(RR.string.ab_repeat)))
        options.add(PlayerOption(ID_SAVE_PLAYLIST, RR.drawable.ic_addtoplaylist, res.getString(RR.string.playlist_save)))
        if (service.playlistManager.player.canDoPassthrough() && settings.getString("aout", "0") == "0")
            options.add(PlayerOption(ID_PASSTHROUGH, RR.drawable.ic_passthrough, res.getString(RR.string.audio_digital_title)))
        if (video)
            options.add(PlayerOption(ID_VIDEO_CONTROLS_SETTING, RR.drawable.ic_video_controls, res.getString(RR.string.controls_setting)))

        if (!Settings.showTvUi) {
            if (video) {
                options.add(PlayerOption(ID_SHOW_VIDEO_TIPS, RR.drawable.ic_videotips, res.getString(RR.string.tips_title)))
            } else {
                options.add(PlayerOption(ID_AUDIO_CONTROLS_SETTING, RR.drawable.ic_audio_controls, res.getString(RR.string.controls_setting)))
                options.add(PlayerOption(ID_SHOW_AUDIO_TIPS, RR.drawable.ic_audiotips, res.getString(RR.string.audio_player_tips)))
                options.add(PlayerOption(ID_SHOW_PLAYLIST_TIPS, RR.drawable.ic_playlisttips, res.getString(RR.string.playlist_tips)))
            }
        }
        (recyclerview.adapter as OptionsAdapter).update(options)
    }

    fun show() {
        activity.findViewById<ViewStubCompat>(R.id.player_options_stub)?.let {
            rootView = it.inflate() as FrameLayout
            recyclerview = rootView.findViewById(R.id.options_list)
            val browseFrameLayout =  rootView.findViewById<BrowseFrameLayout>(R.id.options_background)
            browseFrameLayout.onFocusSearchListener = OnFocusSearchListener { focused, _ ->
                if (recyclerview.hasFocus()) focused // keep focus on recyclerview! DO NOT return recyclerview, but focused, which is a child of the recyclerview
                else null // someone else will find the next focus
            }
            service.lifecycle.addObserver(this)
            activity.lifecycle.addObserver(this)
            if (recyclerview.layoutManager == null) recyclerview.layoutManager = LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
            recyclerview.adapter = OptionsAdapter()
            recyclerview.itemAnimator = null

            rootView.setOnClickListener { hide() }
        }
        val windowInfoLayout = if (activity is VideoPlayerActivity) activity.windowLayoutInfo else if (activity is BaseActivity) activity.windowLayoutInfo else null
        val foldingFeature = windowInfoLayout?.displayFeatures?.firstOrNull() as? FoldingFeature
        if (foldingFeature?.isSeparating == true && foldingFeature.occlusionType == FoldingFeature.OcclusionType.FULL && foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL) {
            val halfScreenSize = activity.getScreenHeight() - foldingFeature.bounds.bottom
            val lp = (rootView.layoutParams as ViewGroup.MarginLayoutParams)
            lp.height = halfScreenSize
            if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.BOTTOM
            rootView.layoutParams = lp
        } else {
             val lp = (rootView.layoutParams as ViewGroup.MarginLayoutParams)
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.BOTTOM
            rootView.layoutParams = lp
        }
        setup()
        rootView.visibility = View.VISIBLE
        if (Settings.showTvUi) AppScope.launch {
            withContext(Dispatchers.IO){ delay(100L) }
            val position = (recyclerview.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            (recyclerview.layoutManager as LinearLayoutManager).findViewByPosition(position)?.requestFocus()
        } else if (activity.isTalkbackIsEnabled()) {
            AppScope.launch {
                withContext(Dispatchers.IO){ delay(100L) }
                val linearLayoutManager = recyclerview.layoutManager as LinearLayoutManager
                linearLayoutManager.findViewByPosition(linearLayoutManager.findFirstVisibleItemPosition())?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            }
        }
    }

    fun hide() {
        rootView.visibility = View.GONE
    }

    fun setBookmarkClickedListener(listener:()->Unit) {
        this.bookmarkClickedListener = listener
    }

    fun onClick(option: PlayerOption) {
        when (option.id) {
            ID_SLEEP -> {
                showFragment(ID_SLEEP)
            }
            ID_PLAY_AS_AUDIO -> (activity as VideoPlayerActivity).switchToAudioMode(true)
            ID_PLAY_AS_VIDEO -> {
                val audioPlayerContainerActivity = activity as AudioPlayerContainerActivity
                audioPlayerContainerActivity.audioPlayer.onResumeToVideoClick()
            }
            ID_POPUP_VIDEO -> {
                (activity as VideoPlayerActivity).switchToPopup()
                hide()
            }
            ID_REPEAT -> setRepeatMode()
            ID_SHUFFLE -> {
                service.shuffle()
                setShuffle()
            }
            ID_PASSTHROUGH -> togglePassthrough()
            ID_ABREPEAT -> {
                hide()
                service.playlistManager.toggleABRepeat()
            }
            ID_LOCK_PLAYER -> {
                hide()
                (activity as VideoPlayerActivity).toggleLock()
            }
            ID_VIDEO_STATS -> {
                hide()
                service.playlistManager.toggleStats()
            }
            ID_SHOW_VIDEO_TIPS -> {
                hide()
                (activity as VideoPlayerActivity).tipsDelegate.init()
            }
            ID_SHOW_AUDIO_TIPS -> {
                hide()
                val audioPlayerContainerActivity = activity as AudioPlayerContainerActivity
                audioPlayerContainerActivity.findViewById<ViewStubCompat>(R.id.audio_player_tips)?.let {
                    audioPlayerContainerActivity.tipsDelegate.init(it)
                }
            }
            ID_SHOW_PLAYLIST_TIPS -> {
                hide()
                val audioPlayerContainerActivity = activity as AudioPlayerContainerActivity
                audioPlayerContainerActivity.findViewById<ViewStubCompat>(R.id.audio_playlist_tips)?.let {
                    audioPlayerContainerActivity.playlistTipsDelegate.init(it)
                }
            }
            ID_BOOKMARK -> {
                hide()
                bookmarkClickedListener.invoke()
            }
            ID_VIDEO_CONTROLS_SETTING -> {
                hide()
                val videoControlsSettingsDialog = VideoControlsSettingsDialog()
                videoControlsSettingsDialog.show(activity.supportFragmentManager, "fragment_video_controls_settings")
            }
            ID_AUDIO_CONTROLS_SETTING -> {
                hide()
                val audioControlsSettingsDialog = AudioControlsSettingsDialog()
                audioControlsSettingsDialog.show(activity.supportFragmentManager, "fragment_audio_controls_settings")
            }
            else -> showFragment(option.id)
        }
    }

    private fun showFragment(id: Long) {
        val newFragment: DialogFragment
        val tag: String
        when (id) {
            ID_PLAYBACK_SPEED -> {
                newFragment = PlaybackSpeedDialog.newInstance()
                tag = "playback_speed"
            }
            ID_JUMP_TO -> {
                newFragment = JumpToTimeDialog.newInstance()
                tag = "time"
            }
            ID_SLEEP -> {
                newFragment = SleepTimerDialog.newInstance()
                tag = "time"
            }
            ID_CHAPTER_TITLE -> {
                newFragment = SelectChapterDialog.newInstance()
                tag = "select_chapter"
            }
            ID_EQUALIZER -> {
                newFragment = EqualizerFragment.newInstance()
                tag = "equalizer"
            }
            ID_SAVE_PLAYLIST -> {
                activity.addToPlaylist(service.media)
                hide()
                return
            }
            else -> return
        }
        if (newFragment is VLCBottomSheetDialogFragment && activity is VideoPlayerActivity)
            newFragment.onDismissListener = DialogInterface.OnDismissListener { activity.overlayDelegate.dimStatusBar(true) }
        newFragment.show(activity.supportFragmentManager, tag)
        hide()
    }

    private fun showValueControls(action: Int) {
        val controller = (activity as? VideoPlayerActivity)?.delayDelegate ?: return
        when (action) {
            ACTION_AUDIO_DELAY -> controller.showAudioDelaySetting()
            ACTION_SPU_DELAY -> controller.showSubsDelaySetting()
            else -> return
        }
        hide()
    }

    private fun setRepeatMode() {
        when (service.repeatType) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> {
                repeatBinding.optionIcon.setImageResource(RR.drawable.ic_repeat_one)
                service.repeatType = PlaybackStateCompat.REPEAT_MODE_ONE
                repeatBinding.root.contentDescription = repeatBinding.root.context.getString(RR.string.repeat_single)
            }
            PlaybackStateCompat.REPEAT_MODE_ONE -> if (service.hasPlaylist()) {
                repeatBinding.optionIcon.setImageResource(RR.drawable.ic_repeat_all)
                service.repeatType = PlaybackStateCompat.REPEAT_MODE_ALL
                repeatBinding.root.contentDescription = repeatBinding.root.context.getString(RR.string.repeat_all)
            } else {
                repeatBinding.optionIcon.setImageResource(RR.drawable.ic_repeat)
                service.repeatType = PlaybackStateCompat.REPEAT_MODE_NONE
                repeatBinding.root.contentDescription = repeatBinding.root.context.getString(RR.string.repeat)
            }
            PlaybackStateCompat.REPEAT_MODE_ALL -> {
                repeatBinding.optionIcon.setImageResource(RR.drawable.ic_repeat)
                service.repeatType = PlaybackStateCompat.REPEAT_MODE_NONE
                repeatBinding.root.contentDescription = repeatBinding.root.context.getString(RR.string.repeat)
            }
        }
    }

    private fun setShuffle() {
        shuffleBinding.optionIcon.setImageResource(if (service.isShuffling) RR.drawable.ic_shuffle_on_48dp else RR.drawable.ic_shuffle)
        shuffleBinding.root.contentDescription = shuffleBinding.root.context.getString(if (service.isShuffling) RR.string.shuffle_on else RR.string.shuffle)
    }

    private fun initShuffle(binding: PlayerOptionItemBinding) {
        shuffleBinding = binding
        AppScope.launch(Dispatchers.Main) {
            shuffleBinding.optionIcon.setImageResource(if (service.isShuffling) RR.drawable.ic_shuffle_on_48dp else RR.drawable.ic_shuffle)
            shuffleBinding.root.contentDescription = shuffleBinding.root.context.getString(if (service.isShuffling) RR.string.shuffle_on else RR.string.shuffle)
        }
    }

    private fun initRepeat(binding: PlayerOptionItemBinding) {
        repeatBinding = binding
        AppScope.launch(Dispatchers.Main) {
            repeatBinding.optionIcon.setImageResource(when (service.repeatType) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> RR.drawable.ic_repeat_one
                PlaybackStateCompat.REPEAT_MODE_ALL -> RR.drawable.ic_repeat_all
                else -> RR.drawable.ic_repeat
            })
            repeatBinding.root.contentDescription = repeatBinding.root.context.getString(when (service.repeatType) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> RR.string.repeat_single
                PlaybackStateCompat.REPEAT_MODE_ALL -> RR.string.repeat_all
                else -> RR.string.repeat
            })
        }
    }

    private fun togglePassthrough() {
        val enabled = !VLCOptions.isAudioDigitalOutputEnabled(settings)
        if (service.setAudioDigitalOutputEnabled(enabled)) {
            ptBinding.optionIcon.setImageResource(if (enabled) RR.drawable.ic_passthrough_on
            else UiTools.getResourceFromAttribute(activity, RR.attr.ic_passthrough))
            VLCOptions.setAudioDigitalOutputEnabled(settings, enabled)
            toast.setText(res.getString(if (enabled) RR.string.audio_digital_output_enabled else RR.string.audio_digital_output_disabled))
        } else
            toast.setText(RR.string.audio_digital_failed)
        toast.show()
    }

    fun isShowing() = rootView.visibility == View.VISIBLE

    private inner class OptionsAdapter : DiffUtilAdapter<PlayerOption, OptionsAdapter.ViewHolder>() {

        private lateinit var layountInflater: LayoutInflater

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            if (!this::layountInflater.isInitialized) layountInflater = LayoutInflater.from(parent.context)
            return ViewHolder(PlayerOptionItemBinding.inflate(layountInflater, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = dataset[position]
            holder.binding.option = option
            when (option.id) {
                ID_ABREPEAT -> abrBinding = holder.binding
                ID_PASSTHROUGH -> ptBinding = holder.binding
                ID_REPEAT -> initRepeat(holder.binding)
                ID_SHUFFLE -> initShuffle(holder.binding)
                ID_SLEEP -> sleepBinding = holder.binding
            }
            holder.binding.optionIcon.setImageResource(option.icon)
        }

        inner class ViewHolder(val binding: PlayerOptionItemBinding) : RecyclerView.ViewHolder(binding.root) {

            init {
                itemView.setOnClickListener { onClick(dataset[layoutPosition]) }
            }
        }
    }
}

data class PlayerOption(val id: Long, val icon: Int, val title: String)
