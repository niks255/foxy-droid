package nya.kitsunyan.foxydroid.screen

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Parcel
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.database.CursorOwner
import nya.kitsunyan.foxydroid.installer.AppInstaller
import nya.kitsunyan.foxydroid.utility.KParcelable
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import nya.kitsunyan.foxydroid.utility.extension.resources.getDrawableFromAttr
import nya.kitsunyan.foxydroid.utility.extension.text.nullIfEmpty


abstract class ScreenActivity: FragmentActivity() {
  companion object {
    var runUpdate: Boolean = false
    private const val STATE_FRAGMENT_STACK = "fragmentStack"
  }

  sealed class SpecialIntent {
    object Installed: SpecialIntent()
    object Updates : SpecialIntent()
    class Install(val packageName: String?, val cacheFileName: String?) : SpecialIntent()
  }

  private class FragmentStackItem(val className: String, val arguments: Bundle?,
    val savedState: Fragment.SavedState?): KParcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
      dest.writeString(className)
      dest.writeByte(if (arguments != null) 1 else 0)
      arguments?.writeToParcel(dest, flags)
      dest.writeByte(if (savedState != null) 1 else 0)
      savedState?.writeToParcel(dest, flags)
    }

    companion object {
      @Suppress("unused") @JvmField val CREATOR = KParcelable.creator {
        val className = it.readString()!!
        val arguments = if (it.readByte().toInt() == 0) null else Bundle.CREATOR.createFromParcel(it)
        arguments?.classLoader = ScreenActivity::class.java.classLoader
        val savedState = if (it.readByte().toInt() == 0) null else Fragment.SavedState.CREATOR.createFromParcel(it)
        FragmentStackItem(className, arguments, savedState)
      }
    }
  }

  lateinit var cursorOwner: CursorOwner
    private set

  private val fragmentStack = mutableListOf<FragmentStackItem>()

  private val currentFragment: Fragment?
    get() {
      supportFragmentManager.executePendingTransactions()
      return supportFragmentManager.findFragmentById(R.id.main_content)
    }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(Utils.configureLocale(base))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(Preferences[Preferences.Key.Theme].getResId(resources.configuration))
    super.onCreate(savedInstanceState)

    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    addContentView(FrameLayout(this).apply { id = R.id.main_content },
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    if (savedInstanceState == null) {
      cursorOwner = CursorOwner()
      supportFragmentManager.beginTransaction()
        .add(cursorOwner, CursorOwner::class.java.name)
        .commit()
    } else {
      cursorOwner = supportFragmentManager
        .findFragmentByTag(CursorOwner::class.java.name) as CursorOwner
    }

    savedInstanceState?.getParcelableArrayList<FragmentStackItem>(STATE_FRAGMENT_STACK)
      ?.let { fragmentStack += it }
    if (savedInstanceState == null) {
      replaceFragment(TabsFragment(), null)
      if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
        handleIntent(intent)
      }
    }

    @SuppressLint("BatteryLife")
    if (Android.sdk(VERSION_CODES.M) && !Preferences[Preferences.Key.BatteryOptimizationAlert]) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      if (!powerManager.isIgnoringBatteryOptimizations(this.packageName)) {
        AlertDialog.Builder(this)
          .setTitle(R.string.ignore_battery_optimization_title)
          .setMessage(R.string.ignore_battery_optimization_message)
          .setPositiveButton(R.string.dialog_approve) { _, _ ->
            val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + this.packageName)
            try {
              startActivity(intent)
            } catch (e: ActivityNotFoundException) {
              Toast.makeText(
                this,
                R.string.ignore_battery_optimization_not_supported,
                Toast.LENGTH_LONG
              ).show()
              Preferences[Preferences.Key.IgnoreBatteryOptimizationUnsupported] = true
            }
            Preferences[Preferences.Key.BatteryOptimizationAlert] = true
          }
          .setNeutralButton(R.string.dialog_refuse) { _: DialogInterface?, _: Int ->
            Preferences[Preferences.Key.BatteryOptimizationAlert] = true
          }
          .show()
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelableArrayList(STATE_FRAGMENT_STACK, ArrayList(fragmentStack))
  }

  override fun onBackPressed() {
    val currentFragment = currentFragment
    if (!(currentFragment is ScreenFragment && currentFragment.onBackPressed())) {
      hideKeyboard()
      if (!popFragment()) {
        super.onBackPressed()
      }
    }
  }

  private fun replaceFragment(fragment: Fragment, open: Boolean?) {
    if (open != null) {
      currentFragment?.view?.translationZ = (if (open) Int.MIN_VALUE else Int.MAX_VALUE).toFloat()
    }
    supportFragmentManager
      .beginTransaction()
      .apply {
        if (open != null) {
          setCustomAnimations(if (open) R.animator.slide_in else 0,
            if (open) R.animator.slide_in_keep else R.animator.slide_out)
        }
      }
      .replace(R.id.main_content, fragment)
      .commit()
  }

  private fun pushFragment(fragment: Fragment) {
    currentFragment?.let { fragmentStack.add(FragmentStackItem(it::class.java.name, it.arguments,
      supportFragmentManager.saveFragmentInstanceState(it))) }
    replaceFragment(fragment, true)
  }

  private fun popFragment(): Boolean {
    return fragmentStack.isNotEmpty() && run {
      val stackItem = fragmentStack.removeAt(fragmentStack.size - 1)
      val fragment = Class.forName(stackItem.className).newInstance() as Fragment
      stackItem.arguments?.let(fragment::setArguments)
      stackItem.savedState?.let(fragment::setInitialSavedState)
      replaceFragment(fragment, false)
      true
    }
  }

  private fun hideKeyboard() {
    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
      ?.hideSoftInputFromWindow((currentFocus ?: window.decorView).windowToken, 0)
  }

  override fun onAttachFragment(fragment: Fragment) {
    super.onAttachFragment(fragment)
    hideKeyboard()
  }

  internal fun onToolbarCreated(toolbar: Toolbar) {
    if (fragmentStack.isNotEmpty()) {
      toolbar.navigationIcon = toolbar.context.getDrawableFromAttr(android.R.attr.homeAsUpIndicator)
      toolbar.setNavigationOnClickListener { onBackPressed() }
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  protected val Intent.packageName: String?
    get() {
      val uri = data
      return when {
        uri?.scheme == "package" || uri?.scheme == "fdroid.app" -> {
          uri.schemeSpecificPart?.nullIfEmpty()
        }
        uri?.scheme == "market" && uri.host == "details" -> {
          uri.getQueryParameter("id")?.nullIfEmpty()
        }
        uri != null && uri.scheme in setOf("http", "https") -> {
          val host = uri.host.orEmpty()
          if (host == "f-droid.org" || host == "apt.izzysoft.de" ||
              host.endsWith(".f-droid.org")) {
            uri.lastPathSegment?.nullIfEmpty()
          } else {
            null
          }
        }
        else -> {
          null
        }
      }
    }

  protected fun handleSpecialIntent(specialIntent: SpecialIntent) {
    when (specialIntent) {
      is SpecialIntent.Updates -> {
        if (currentFragment !is TabsFragment) {
          fragmentStack.clear()
          replaceFragment(TabsFragment(), true)
        }
        val tabsFragment = currentFragment as TabsFragment
        tabsFragment.selectUpdates()
      }
      is SpecialIntent.Installed -> {
        if (currentFragment !is TabsFragment) {
          fragmentStack.clear()
          replaceFragment(TabsFragment(), true)
        }
        val tabsFragment = currentFragment as TabsFragment
        tabsFragment.selectInstalled()
      }
      is SpecialIntent.Install -> openAppPage(specialIntent.packageName,
                                              specialIntent.cacheFileName)
    }::class
  }

  private fun openAppPage(packageName: String?,
                          cacheFileName: String? = null) {
    if (!packageName.isNullOrEmpty()) {
      val fragment = currentFragment
      if (fragment !is ProductFragment || fragment.packageName != packageName) {
        pushFragment(ProductFragment(packageName))
      }
      lifecycleScope.launch {
        cacheFileName?.let {
          AppInstaller.getInstance(this@ScreenActivity)
            ?.defaultInstaller?.install(packageName, it)
        }
      }
      Unit
    }
  }

  open fun handleIntent(intent: Intent?) {
    when (intent?.action) {
      Intent.ACTION_VIEW -> {
        val packageName = intent.packageName
        openAppPage(packageName)
      }
    }
  }

  internal fun navigateProduct(packageName: String) = pushFragment(ProductFragment(packageName))
  internal fun navigateRepositories() = pushFragment(RepositoriesFragment())
  internal fun navigatePreferences() = pushFragment(PreferencesFragment())
  internal fun navigateAddRepository() = pushFragment(EditRepositoryFragment(null))
  internal fun navigateRepository(repositoryId: Long) = pushFragment(RepositoryFragment(repositoryId))
  internal fun navigateEditRepository(repositoryId: Long) = pushFragment(EditRepositoryFragment(repositoryId))
}
