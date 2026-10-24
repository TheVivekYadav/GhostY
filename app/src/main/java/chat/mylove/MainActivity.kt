
package chat.mylove

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import java.io.ByteArrayOutputStream
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
class MainActivity : AppCompatActivity() {

    private lateinit var mainContent: FrameLayout
    private lateinit var navHome: TextView
    private lateinit var navChats: TextView
    private lateinit var navDevices: TextView
    private lateinit var navSettings: TextView

    private lateinit var appRoot: View
    private lateinit var appHeader: View
    private lateinit var topDivider: View
    private lateinit var bottomDivider: View
    private lateinit var bottomNavigation: View

    private lateinit var webRtcClient: WebRtcClient

    private var selectedTab = Tab.HOME
    private var standaloneScreen = false
    private var currentPairingRequestId: String? = null
    private var currentReconnectRequestId: String? = null
    private var currentInvitation: JSONObject? = null

    // Active chat UI references. The WebRTC client remains the message transport.
    private var chatScreenOpen = false
    private var messagesContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var messageInput: EditText? = null
    private var chatPeerStatus: TextView? = null
    private val pendingChatMessages = mutableListOf<Pair<String, Boolean>>()
    private lateinit var chatStore: ChatStore
    private var currentConversationId: String = ChatStore.DEFAULT_CONVERSATION_ID
    private var currentConversationName: String = "Paired device"
    private var isDataChannelOpen = false
    private var chatsAdapter: ChatsAdapter? = null
    private var pendingCallVideo: Boolean? = null
    private var pendingAcceptCall = false
    private var remoteVideoRenderer: SurfaceViewRenderer? = null
    private var localVideoRenderer: SurfaceViewRenderer? = null
    private var videoPanel: View? = null
    private var chatScrollContainer: View? = null
    private var chatFeatureButtons: View? = null
    private var chatComposer: View? = null
    private var pendingReceivedFile: Triple<String, String, ByteArray>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendPickedFile(uri)
    }

    // Select a QR screenshot/image received through WhatsApp, Gallery, or Files.
    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) decodeQrImage(uri)
    }
    private val saveReceivedFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingReceivedFile
        if (uri != null && file != null) {
            try { contentResolver.openOutputStream(uri)?.use { it.write(file.third) }; Toast.makeText(this, "Saved ${file.first}", Toast.LENGTH_LONG).show() }
            catch (e: Exception) { Toast.makeText(this, "Could not save file: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        pendingReceivedFile = null
    }

    private enum class Tab {
        HOME, CHATS, DEVICES, SETTINGS
    }

    // App lock
    private val lockPrefs by lazy {
        getSharedPreferences("ghostlink_security", MODE_PRIVATE)
    }

    private var needsUnlock = false
    private var isUnlocked = false
    private var isAuthenticating = false
    private var unavailableDialogShowing = false

    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val appRootView = findViewById<View>(R.id.main)

        val initialLeft = appRootView.paddingLeft
        val initialTop = appRootView.paddingTop
        val initialRight = appRootView.paddingRight
        val initialBottom = appRootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(appRootView) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(appRootView)
        // Intercept Android system Back through the modern dispatcher. This is more reliable
        // than relying only on the deprecated Activity.onBackPressed() callback.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleGhostLinkBack()
            }
        })

        appRoot = findViewById(R.id.main)
        mainContent = findViewById(R.id.mainContent)
        navHome = findViewById(R.id.navHome)
        navChats = findViewById(R.id.navChats)
        navDevices = findViewById(R.id.navDevices)
        navSettings = findViewById(R.id.navSettings)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        // Matches the child order in your existing activity_main.xml.
        val root = findViewById<ViewGroup>(R.id.main)
        appHeader = root.getChildAt(0)
        topDivider = root.getChildAt(1)
        bottomDivider = root.getChildAt(3)

        if (lockPrefs.getBoolean("app_lock_enabled", false)) {
            needsUnlock = true
            appRoot.visibility = View.INVISIBLE
        }

        chatStore = ChatStore(this)

        webRtcClient = WebRtcClient(
            context = this,
            onStatus = { status ->
                Log.d("GhostLink", status)
                runOnUiThread {
                    chatPeerStatus?.text = status
                    if (status == "DataChannel: OPEN") {
                        isDataChannelOpen = true
                        ensureCurrentConversation()
                        if (chatScreenOpen) {
                            mainContent.findViewById<View>(R.id.sendButton)?.isEnabled = true
                            messageInput?.hint = "Message"
                            chatPeerStatus?.text = "Connected"
                        } else {
                            openChatScreen()
                        }
                    } else if (status.startsWith("DataChannel:") || status.contains("disconnected", true) || status.contains("Session ended", true)) {
                        isDataChannelOpen = false
                    }
                }
            },
            onMessage = { message, outgoing ->
                runOnUiThread {
                    ensureCurrentConversation()
                    chatStore.addMessage(currentConversationId, message, outgoing)
                    if (chatScreenOpen) {
                        appendChatMessage(message, outgoing)
                    } else {
                        pendingChatMessages.add(message to outgoing)
                    }
                    if (selectedTab == Tab.CHATS && !standaloneScreen) refreshChatsList()
                }
            },
            onPairingEvent = { event ->
                runOnUiThread { handlePairingEvent(event) }
            },
            onFeatureEvent = { event -> runOnUiThread { handleFeatureEvent(event) } },
            onFileReceived = { name, mime, bytes -> runOnUiThread {
                // Keep received content inside app-private storage. Nothing is written to
                // Downloads/Gallery until the user explicitly taps Save.
                val privateDir = java.io.File(filesDir, "received_media").apply { mkdirs() }
                val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "received-file" }
                val privateFile = java.io.File(privateDir, "${System.currentTimeMillis()}_$safeName")
                try { privateFile.writeBytes(bytes) } catch (e: Exception) {
                    Toast.makeText(this, "Could not cache received file", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                addAttachmentBubble(safeName, mime, bytes, outgoing = false, privatePath = privateFile.absolutePath)
            } },
            onRemoteVideo = { track -> runOnUiThread {
                val renderer = remoteVideoRenderer
                if (renderer != null) {
                    if (track != null) videoPanel?.visibility = View.VISIBLE
                    else videoPanel?.visibility = View.GONE
                }
            } },
            onLocalVideo = { track -> runOnUiThread {
                val renderer = localVideoRenderer
                if (renderer != null && track != null) videoPanel?.visibility = View.VISIBLE
                else if (track == null && remoteVideoRenderer == null) videoPanel?.visibility = View.GONE
            } }
        )

        navHome.setOnClickListener { showTab(Tab.HOME) }
        navChats.setOnClickListener { showTab(Tab.CHATS) }
        navDevices.setOnClickListener { showTab(Tab.DEVICES) }
        navSettings.setOnClickListener { showTab(Tab.SETTINGS) }

        showTab(Tab.HOME)
        // Register this phone as online so saved peers can request reconnection.
        webRtcClient.connect()
    }

    // ---------------------------------------------------------
    // App lock
    // ---------------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (
            ::appRoot.isInitialized &&
            lockPrefs.getBoolean("app_lock_enabled", false) &&
            needsUnlock &&
            !isAuthenticating &&
            !unavailableDialogShowing
        ) {
            showAppLock()
        }
    }

    override fun onStop() {
        super.onStop()

        if (
            lockPrefs.getBoolean("app_lock_enabled", false) &&
            isUnlocked
        ) {
            needsUnlock = true
            isUnlocked = false
        }
    }

    private fun setupSecuritySettings(content: View) {
        val lockSwitch = content.findViewById<CompoundButton>(
            R.id.appLockSwitch
        )

        lockSwitch.isChecked =
            lockPrefs.getBoolean("app_lock_enabled", false)

        lockSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                val available = BiometricManager
                    .from(this)
                    .canAuthenticate(authenticators) ==
                        BiometricManager.BIOMETRIC_SUCCESS

                if (!available) {
                    lockSwitch.isChecked = false

                    Toast.makeText(
                        this,
                        "Set up a secure screen lock first.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@setOnCheckedChangeListener
                }

                lockPrefs.edit()
                    .putBoolean("app_lock_enabled", true)
                    .apply()

                isUnlocked = true
                needsUnlock = false

                Toast.makeText(
                    this,
                    "App Lock enabled",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                lockPrefs.edit()
                    .putBoolean("app_lock_enabled", false)
                    .apply()

                needsUnlock = false
                isUnlocked = true
            }
        }
    }

    private fun showAppLock() {
        if (isAuthenticating || isFinishing || isDestroyed) return

        val available = BiometricManager
            .from(this)
            .canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS

        if (!available) {
            showScreenLockRequiredDialog()
            return
        }

        appRoot.visibility = View.INVISIBLE

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    isAuthenticating = false
                    isUnlocked = true
                    needsUnlock = false
                    unavailableDialogShowing = false

                    if (!isFinishing && !isDestroyed) {
                        appRoot.visibility = View.VISIBLE
                    }
                }

                override fun onAuthenticationFailed() {
                    // Keep private UI hidden.
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    isAuthenticating = false

                    if (!isFinishing) {
                        Toast.makeText(
                            this@MainActivity,
                            "GhostLink remains locked.",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock GhostLink")
            .setSubtitle("Authenticate to access private chats")
            .setAllowedAuthenticators(authenticators)
            .build()

        isAuthenticating = true
        prompt.authenticate(promptInfo)
    }

    private fun showScreenLockRequiredDialog() {
        if (unavailableDialogShowing || isFinishing || isDestroyed) return

        unavailableDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("Secure your device")
            .setMessage(
                "GhostLink is locked. Set up a secure screen lock " +
                        "or supported biometric in Android Settings."
            )
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                    )
                } finally {
                    finish()
                }
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .show()
    }

    // ---------------------------------------------------------
    // Bottom navigation
    // ---------------------------------------------------------

    private fun showTab(tab: Tab) {
        selectedTab = tab
        mainContent.removeAllViews()

        val layoutId = when (tab) {
            Tab.HOME -> R.layout.fragment_home
            Tab.CHATS -> R.layout.fragment_chats
            Tab.DEVICES -> R.layout.fragment_devices
            Tab.SETTINGS -> R.layout.fragment_settings
        }

        val content = layoutInflater.inflate(
            layoutId,
            mainContent,
            false
        )

        mainContent.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        updateNavigationAppearance()
        setupTabActions(tab, content)
    }

    private fun setupTabActions(tab: Tab, content: View) {
        when (tab) {
            Tab.HOME -> {
                content.findViewById<View>(
                    R.id.homeCreateQrButton
                ).setOnClickListener {
                    openCreateQrScreen()
                }

                content.findViewById<View>(
                    R.id.homeScanQrButton
                ).setOnClickListener {
                    showQrInputOptions()
                }
            }

            Tab.DEVICES -> {
                content.findViewById<View>(
                    R.id.devicesCreateQrButton
                ).setOnClickListener {
                    openCreateQrScreen()
                }

                content.findViewById<View>(
                    R.id.devicesScanQrButton
                ).setOnClickListener {
                    showQrInputOptions()
                }
            }

            Tab.CHATS -> setupChatsList(content)

            Tab.SETTINGS -> setupSecuritySettings(content)
        }
    }

    private fun setupChatsList(content: View) {
        val recycler = content.findViewById<RecyclerView>(R.id.chatsRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        chatsAdapter = ChatsAdapter { conversation ->
            currentConversationId = conversation.id
            currentConversationName = conversation.name
            val connected = webRtcClientStatusIsOpen()
            if (connected) {
                openChatScreen()
            } else {
                openSavedConversationScreen()
                val pairToken = chatStore.getPairToken(conversation.id)
                if (pairToken != null) {
                    webRtcClient.requestReconnect(conversation.id, pairToken)
                    chatPeerStatus?.text = "Requesting approval to reconnect..."
                    Toast.makeText(this, "Reconnect request sent. The other device must accept it.", Toast.LENGTH_LONG).show()
                } else {
                    chatPeerStatus?.text = "Offline ? pair again with QR to enable reconnect"
                    Toast.makeText(this, "This chat has no saved trusted-device credential yet. Pair again using QR.", Toast.LENGTH_LONG).show()
                }
            }
        }
        recycler.adapter = chatsAdapter
        refreshChatsList()
    }

    private fun refreshChatsList() {
        if (!::chatStore.isInitialized) return
        val content = mainContent.getChildAt(0) ?: return
        val recycler = content.findViewById<RecyclerView>(R.id.chatsRecyclerView) ?: return
        val empty = content.findViewById<View>(R.id.chatsEmptyState)
        val conversations = chatStore.getConversations()
        empty?.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (conversations.isEmpty()) View.GONE else View.VISIBLE
        chatsAdapter?.submit(conversations)
    }

    private fun ensureCurrentConversation() {
        if (!::chatStore.isInitialized) return
        chatStore.ensureConversation(currentConversationId, currentConversationName)
        refreshChatsList()
    }

    private fun webRtcClientStatusIsOpen(): Boolean = isDataChannelOpen

    private fun openSavedConversationScreen() {
        if (chatScreenOpen || isFinishing || isDestroyed) return
        standaloneScreen = true
        appHeader.visibility = View.GONE
        topDivider.visibility = View.GONE
        bottomDivider.visibility = View.GONE
        bottomNavigation.visibility = View.GONE
        mainContent.removeAllViews()
        val content = layoutInflater.inflate(R.layout.activity_chat, mainContent, false)
        mainContent.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        chatScreenOpen = true
        messagesContainer = content.findViewById(R.id.messagesContainer)
        chatScrollView = content.findViewById(R.id.chatScrollView)
        messageInput = content.findViewById(R.id.messageInput)
        chatPeerStatus = content.findViewById(R.id.chatPeerStatus)
        content.findViewById<TextView>(R.id.chatPeerName).text = currentConversationName
        chatPeerStatus?.text = "Offline ? saved history"
        content.findViewById<View>(R.id.sendButton).isEnabled = false
        content.findViewById<EditText>(R.id.messageInput).hint = "Reconnect to send messages"
        content.findViewById<View>(R.id.chatBackButton).setOnClickListener {
            chatScreenOpen = false
            clearChatViewReferences()
            restoreMainScreen()
        }
        renderSavedMessages()
    }

    private fun renderSavedMessages() {
        messagesContainer?.removeAllViews()
        chatStore.getMessages(currentConversationId).forEach { msg ->
            appendChatMessage(msg.text, msg.outgoing)
        }
    }

    private fun updateNavigationAppearance() {
        val activeColor = 0xFFB8ACFF.toInt()
        val inactiveColor = 0xFF94A3B8.toInt()

        navHome.setTextColor(
            if (selectedTab == Tab.HOME) activeColor else inactiveColor
        )
        navChats.setTextColor(
            if (selectedTab == Tab.CHATS) activeColor else inactiveColor
        )
        navDevices.setTextColor(
            if (selectedTab == Tab.DEVICES) activeColor else inactiveColor
        )
        navSettings.setTextColor(
            if (selectedTab == Tab.SETTINGS) activeColor else inactiveColor
        )

        navHome.isSelected = selectedTab == Tab.HOME
        navChats.isSelected = selectedTab == Tab.CHATS
        navDevices.isSelected = selectedTab == Tab.DEVICES
        navSettings.isSelected = selectedTab == Tab.SETTINGS
    }

    // ---------------------------------------------------------
    // Standalone screens
    // ---------------------------------------------------------

    private fun showStandaloneScreen(layoutId: Int): View {
        standaloneScreen = true

        appHeader.visibility = View.GONE
        topDivider.visibility = View.GONE
        bottomDivider.visibility = View.GONE
        bottomNavigation.visibility = View.GONE

        mainContent.removeAllViews()

        val content = layoutInflater.inflate(
            layoutId,
            mainContent,
            false
        )

        mainContent.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return content
    }

    private fun restoreMainScreen() {
        standaloneScreen = false

        appHeader.visibility = View.VISIBLE
        topDivider.visibility = View.VISIBLE
        bottomDivider.visibility = View.VISIBLE
        bottomNavigation.visibility = View.VISIBLE

        showTab(selectedTab)
    }

    // ---------------------------------------------------------
    // Create invitation QR
    // ---------------------------------------------------------

    private fun openCreateQrScreen() {
        val content = showStandaloneScreen(
            R.layout.activity_create_qr
        )

        content.findViewById<View>(
            R.id.createQrBackButton
        ).setOnClickListener {
            webRtcClient.cancelInvite()
            currentInvitation = null
            restoreMainScreen()
        }

        content.findViewById<View>(
            R.id.cancelQrButton
        ).setOnClickListener {
            webRtcClient.cancelInvite()
            currentInvitation = null
            restoreMainScreen()
        }

        content.findViewById<TextView>(
            R.id.pairingQrStatus
        ).text = "Creating invitation..."

        content.findViewById<TextView>(
            R.id.pairingQrCountdown
        ).text = "Invitation expires in 2 minutes"

        webRtcClient.createInvite()
    }

    private fun renderInvitation(event: JSONObject) {
        val roomId = event.optString("roomId")
        val token = event.optString("token")

        if (roomId.isBlank() || token.isBlank()) {
            showPairingError("Invalid invitation returned by server.")
            return
        }

        val invitation = JSONObject()
            .put("roomId", roomId)
            .put("token", token)

        currentInvitation = invitation

        val content = mainContent.getChildAt(0) ?: return
        val qrImage = content.findViewById<ImageView>(
            R.id.pairingQrImage
        )
        val statusText = content.findViewById<TextView>(
            R.id.pairingQrStatus
        )

        try {
            val matrix = QRCodeWriter().encode(
                invitation.toString(),
                BarcodeFormat.QR_CODE,
                700,
                700
            )

            val bitmap = Bitmap.createBitmap(
                matrix.width,
                matrix.height,
                Bitmap.Config.ARGB_8888
            )

            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix[x, y]) {
                            0xFF000000.toInt()
                        } else {
                            0xFFFFFFFF.toInt()
                        }
                    )
                }
            }

            qrImage.setImageBitmap(bitmap)
            statusText.text = "Scan this QR on your other device."
        } catch (e: Exception) {
            statusText.text = "QR generation failed: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // Scan invitation QR
    // ---------------------------------------------------------

    private fun showQrInputOptions() {
        AlertDialog.Builder(this)
            .setTitle("Connect with QR")
            .setItems(arrayOf("Scan QR with camera", "Import QR image")) { _, choice ->
                when (choice) {
                    0 -> startQrScanner()
                    1 -> qrImagePicker.launch("image/*")
                }
            }
            .show()
    }

    private fun startQrScanner() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan GhostLink pairing QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    private fun decodeQrImage(uri: Uri) {
        // Try progressively smaller bitmaps to reduce memory use for large screenshots.
        try {
            var decodedText: String? = null
            var sampleSize = 1

            while (sampleSize <= 8 && decodedText == null) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                } ?: throw IllegalArgumentException("Could not read the selected image")

                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(
                        pixels, 0, bitmap.width,
                        0, 0, bitmap.width, bitmap.height
                    )
                    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                    decodedText = try {
                        MultiFormatReader().decode(binaryBitmap).text
                    } catch (_: com.google.zxing.NotFoundException) {
                        null
                    } finally {
                        // Reader instances are short-lived; no state is retained between attempts.
                    }
                } finally {
                    bitmap.recycle()
                }

                sampleSize *= 2
            }

            if (decodedText.isNullOrBlank()) {
                showPairingError("No readable QR code found in that image.")
                return
            }

            handleInvitationText(decodedText!!)
        } catch (e: Exception) {
            Log.w("GhostLink", "Could not decode QR image", e)
            showPairingError("Could not read that QR image. Try a clearer screenshot.")
        }
    }

    private fun handleInvitationText(scannedText: String) {
        try {
            val invitation = JSONObject(scannedText)
            val roomId = invitation.getString("roomId")
            val token = invitation.getString("token")

            if (roomId.isBlank() || token.isBlank()) {
                throw IllegalArgumentException("Missing invitation fields")
            }

            val deviceLabel = listOf(
                android.os.Build.MANUFACTURER,
                android.os.Build.MODEL
            ).joinToString(" ").take(40)

            webRtcClient.joinInvite(
                roomId = roomId,
                token = token,
                deviceLabel = deviceLabel
            )

            Toast.makeText(
                this,
                "Pairing request sent. Waiting for approval.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            showPairingError("Invalid GhostLink QR invitation.")
        }
    }

    @Deprecated("Uses ZXing's compatible scanner result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        val result: IntentResult? =
            IntentIntegrator.parseActivityResult(
                requestCode,
                resultCode,
                data
            )

        if (result == null) return
        val scannedText = result.contents ?: return
        handleInvitationText(scannedText)
    }

    // ---------------------------------------------------------
    // Pairing approval
    // ---------------------------------------------------------

    private fun handlePairingEvent(event: JSONObject) {
        when (event.optString("type")) {
            "invite-created" -> {
                if (standaloneScreen) renderInvitation(event)
            }

            "pairing-request" -> showPairingApproval(event)

            "pairing-pending" -> {
                Toast.makeText(
                    this,
                    "Waiting for approval on the other device.",
                    Toast.LENGTH_LONG
                ).show()
            }

            "pairing-request-cancelled" -> {
                showPairingError("Pairing request cancelled.")
                if (standaloneScreen) restoreMainScreen()
            }

            "pairing-approved" -> {
                Toast.makeText(this, "Pairing approved. Connecting...", Toast.LENGTH_LONG).show()
            }

            "trusted-device" -> {
                val peerId = event.optString("peerDeviceId")
                val peerName = event.optString("peerLabel", "Paired device")
                val pairToken = event.optString("pairToken")
                if (peerId.isNotBlank() && pairToken.length >= 32) {
                    currentConversationId = peerId
                    currentConversationName = peerName
                    chatStore.saveTrustedDevice(peerId, peerName, pairToken)
                    refreshChatsList()
                    Toast.makeText(this, "Trusted device saved. Next time, tap its chat to reconnect.", Toast.LENGTH_LONG).show()
                }
            }

            "reconnect-request" -> handleReconnectRequest(event)

            "reconnect-pending" -> {
                Toast.makeText(this, "Waiting for the other device to approve reconnection.", Toast.LENGTH_LONG).show()
            }

            "reconnect-approved" -> {
                chatPeerStatus?.text = "Reconnect approved ? establishing WebRTC..."
            }

            "reconnect-rejected" -> {
                Toast.makeText(this, "The other device rejected reconnection.", Toast.LENGTH_LONG).show()
                if (chatScreenOpen) chatPeerStatus?.text = "Reconnect rejected"
            }

            "reconnect-failed" -> {
                val reason = event.optString("reason", "unavailable")
                val message = if (reason == "peer-offline") "The other device is offline." else "Reconnect failed: $reason"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (chatScreenOpen) chatPeerStatus?.text = message
            }

            "pairing-rejected" -> {
                showPairingError("Pairing was rejected.")
                if (standaloneScreen) restoreMainScreen()
            }

            "joined", "peer-joined" -> {
                ensureCurrentConversation()
            }

            "error" -> {
                showPairingError(
                    event.optString("message", "Pairing failed.")
                )
            }

            "session-ended", "session-expired" -> {
                showPairingError(
                    event.optString("reason", "Session ended.")
                )
            }

            "peer-left" -> {
                showPairingError("The other device disconnected.")
            }
        }
    }

    private fun handleReconnectRequest(event: JSONObject) {
        val requestId = event.optString("requestId")
        val requesterId = event.optString("requesterDeviceId")
        val requesterLabel = event.optString("requesterLabel", "Trusted device")
        val suppliedToken = event.optString("pairToken")
        if (requestId.isBlank() || requesterId.isBlank() ||
            !chatStore.isTrustedPeer(requesterId, suppliedToken)) {
            // Reject unknown or mismatched credentials without presenting a prompt.
            if (requestId.isNotBlank()) webRtcClient.respondToReconnect(requestId, false)
            Log.w("GhostLink", "Rejected reconnect request: local trust check failed")
            return
        }

        currentReconnectRequestId = requestId
        currentConversationId = requesterId
        currentConversationName = chatStore.getConversations()
            .firstOrNull { it.id == requesterId }?.name ?: requesterLabel
        val content = showStandaloneScreen(R.layout.activity_pairing_approval)
        content.findViewById<TextView>(R.id.pairingRequestDescription).text =
            "$requesterLabel wants to reconnect to your saved GhostLink chat.\n\n" +
                    "The saved trusted-device credential matched. Accept only if you expect this connection."
        content.findViewById<TextView>(R.id.pairingRequestStatus).text = "Waiting for your decision"
        val accept = content.findViewById<View>(R.id.acceptPairingButton)
        val reject = content.findViewById<View>(R.id.rejectPairingButton)
        accept.setOnClickListener {
            webRtcClient.respondToReconnect(requestId, true)
            content.findViewById<TextView>(R.id.pairingRequestStatus).text = "Approving reconnection..."
            accept.isEnabled = false
            reject.isEnabled = false
        }
        reject.setOnClickListener {
            webRtcClient.respondToReconnect(requestId, false)
            currentReconnectRequestId = null
            restoreMainScreen()
        }
        content.findViewById<View>(R.id.pairingApprovalBackButton).setOnClickListener {
            // Back is a rejection, never an implicit approval.
            webRtcClient.respondToReconnect(requestId, false)
            currentReconnectRequestId = null
            restoreMainScreen()
        }
    }

    private fun showPairingApproval(event: JSONObject) {
        val requestId = event.optString("requestId")

        if (requestId.isBlank()) {
            showPairingError("Invalid pairing request.")
            return
        }

        currentPairingRequestId = requestId

        val deviceLabel = event.optString(
            "deviceLabel",
            "Unknown device"
        )

        val content = showStandaloneScreen(
            R.layout.activity_pairing_approval
        )

        content.findViewById<TextView>(
            R.id.pairingRequestDescription
        ).text =
            "Device: $deviceLabel\n\n" +
                    "This device wants to pair with GhostLink. " +
                    "Only approve if you initiated this pairing."

        content.findViewById<TextView>(
            R.id.pairingRequestStatus
        ).text = "Waiting for your decision"

        val acceptButton = content.findViewById<View>(
            R.id.acceptPairingButton
        )
        val rejectButton = content.findViewById<View>(
            R.id.rejectPairingButton
        )

        acceptButton.setOnClickListener {
            webRtcClient.respondToPairing(requestId, true)

            content.findViewById<TextView>(
                R.id.pairingRequestStatus
            ).text = "Approving pairing..."

            acceptButton.isEnabled = false
            rejectButton.isEnabled = false
        }

        rejectButton.setOnClickListener {
            webRtcClient.respondToPairing(requestId, false)
            currentPairingRequestId = null
            restoreMainScreen()
        }

        content.findViewById<View>(
            R.id.pairingApprovalBackButton
        ).setOnClickListener {
            // Going back never approves the request.
            restoreMainScreen()
        }
    }

    // ---------------------------------------------------------
    // Chat screen and messages
    // ---------------------------------------------------------

    private fun openChatScreen() {
        if (chatScreenOpen || isFinishing || isDestroyed) return

        standaloneScreen = true
        appHeader.visibility = View.GONE
        topDivider.visibility = View.GONE
        bottomDivider.visibility = View.GONE
        bottomNavigation.visibility = View.GONE
        mainContent.removeAllViews()

        val content = layoutInflater.inflate(R.layout.activity_chat, mainContent, false)
        mainContent.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        chatScreenOpen = true
        messagesContainer = content.findViewById(R.id.messagesContainer)
        chatScrollView = content.findViewById(R.id.chatScrollView)
        messageInput = content.findViewById(R.id.messageInput)
        chatPeerStatus = content.findViewById(R.id.chatPeerStatus)
        content.findViewById<TextView>(R.id.chatPeerName).text = currentConversationName
        chatPeerStatus?.text = "WebRTC data channel connected"
        videoPanel = content.findViewById(R.id.videoPanel)
        chatScrollContainer = content.findViewById(R.id.chatScrollView)
        chatFeatureButtons = content.findViewById(R.id.chatFeatureButtons)
        chatComposer = content.findViewById(R.id.chatComposer)
        remoteVideoRenderer = content.findViewById(R.id.remoteVideoRenderer)
        localVideoRenderer = content.findViewById(R.id.localVideoRenderer)
        try {
            webRtcClient.initializeRenderer(remoteVideoRenderer!!)
            webRtcClient.initializeRenderer(localVideoRenderer!!)
            webRtcClient.attachRemoteRenderer(remoteVideoRenderer!!)
            webRtcClient.attachLocalRenderer(localVideoRenderer!!)
        } catch (e: Exception) { Log.w("GhostLink", "Renderer init failed", e) }

        content.findViewById<View>(R.id.audioCallButton).setOnClickListener { requestCallPermissions(false) }
        content.findViewById<View>(R.id.videoCallButton).setOnClickListener { requestCallPermissions(true) }
        content.findViewById<View>(R.id.attachFileButton).setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        val callControls = content.findViewById<View>(R.id.callControls)
        content.findViewById<View>(R.id.muteCallButton).setOnClickListener {
            callMuted = !callMuted; webRtcClient.setMicrophoneMuted(callMuted)
            (it as android.widget.Button).text = if (callMuted) "Unmute" else "Mute"
        }
        content.findViewById<View>(R.id.speakerCallButton).setOnClickListener {
            speakerEnabled = !speakerEnabled; setSpeakerphone(speakerEnabled)
            (it as android.widget.Button).text = if (speakerEnabled) "Speaker on" else "Speaker"
        }
        content.findViewById<View>(R.id.cameraCallButton).setOnClickListener {
            cameraEnabled = !cameraEnabled; webRtcClient.setCameraEnabled(cameraEnabled)
            (it as android.widget.Button).text = if (cameraEnabled) "Camera off" else "Camera on"
        }
        content.findViewById<View>(R.id.switchCameraButton).setOnClickListener { webRtcClient.switchCamera() }
        content.findViewById<View>(R.id.endCallButton).setOnClickListener { webRtcClient.endCall() }

        content.findViewById<View>(R.id.chatBackButton).setOnClickListener {
            chatScreenOpen = false
            clearChatViewReferences()
            restoreMainScreen()
        }

        content.findViewById<View>(R.id.sendButton).setOnClickListener {
            val input = messageInput ?: return@setOnClickListener
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            if (webRtcClient.sendMessage(text)) {
                input.text.clear()
            } else {
                Toast.makeText(this, "Message could not be sent. Check connection.", Toast.LENGTH_SHORT).show()
            }
        }

        renderSavedMessages()
        pendingChatMessages.clear()
    }

    private fun requestCallPermissions(video: Boolean) {
        val required = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingCallVideo = video
            requestPermissions(missing.toTypedArray(), 7001)
            return
        }
        if (!webRtcClient.startCall(video)) Toast.makeText(this, "Connect to the paired device first", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, if (video) "Calling with video?" else "Calling?", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7001) {
            val video = pendingCallVideo ?: return
            pendingCallVideo = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingAcceptCall) { pendingAcceptCall = false; webRtcClient.acceptCall(video) }
                else requestCallPermissions(video)
            } else { pendingAcceptCall = false; Toast.makeText(this, "Microphone${if (video) " and camera" else ""} permission is required", Toast.LENGTH_LONG).show() }
        }
    }

    private fun handleFeatureEvent(event: JSONObject) {
        when (event.optString("kind")) {
            "call-invite" -> {
                val video = event.optBoolean("video")
                AlertDialog.Builder(this).setTitle(if (video) "Incoming video call" else "Incoming audio call")
                    .setMessage("Paired device is calling you.")
                    .setPositiveButton("Accept") { _, _ ->
                        val required = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
                        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                        if (missing.isNotEmpty()) { pendingCallVideo = video; pendingAcceptCall = true; requestPermissions(missing.toTypedArray(), 7001) }
                        else webRtcClient.acceptCall(video)
                    }.setNegativeButton("Decline") { _, _ -> webRtcClient.rejectCall() }.show()
            }
            "call-accepted" -> Toast.makeText(this, "Call accepted; connecting?", Toast.LENGTH_SHORT).show()
            "call-active" -> {
                val isVideo = event.optBoolean("video")
                videoPanel?.visibility = if (isVideo) View.VISIBLE else View.GONE
                // Keep chat, file sharing, and call buttons available on BOTH peers.
                // Only hide the message history while video is taking the main area.
                chatScrollContainer?.visibility = if (isVideo) View.GONE else View.VISIBLE
                chatFeatureButtons?.visibility = View.VISIBLE
                chatComposer?.visibility = View.VISIBLE
                mainContent.findViewById<View?>(R.id.callControls)?.visibility = View.VISIBLE
                mainContent.findViewById<View?>(R.id.cameraCallButton)?.visibility = if (isVideo) View.VISIBLE else View.GONE
                mainContent.findViewById<View?>(R.id.switchCameraButton)?.visibility = if (isVideo) View.VISIBLE else View.GONE
                Log.d("GhostLink", "UI call-active: video=$isVideo; chat/file controls remain enabled")
                Toast.makeText(this, if (isVideo) "Video call connecting" else "Audio call connecting", Toast.LENGTH_SHORT).show()
            }
            "call-rejected" -> Toast.makeText(this, "Call declined", Toast.LENGTH_SHORT).show()
            "call-busy" -> Toast.makeText(this, "The other device is already in a call", Toast.LENGTH_LONG).show()
            "call-ended" -> {
                videoPanel?.visibility = View.GONE
                chatScrollContainer?.visibility = View.VISIBLE
                chatFeatureButtons?.visibility = View.VISIBLE
                chatComposer?.visibility = View.VISIBLE
                mainContent.findViewById<View?>(R.id.callControls)?.visibility = View.GONE
                mainContent.findViewById<View?>(R.id.cameraCallButton)?.visibility = View.VISIBLE
                mainContent.findViewById<View?>(R.id.switchCameraButton)?.visibility = View.VISIBLE
                callMuted = false; cameraEnabled = true; speakerEnabled = true; setSpeakerphone(false)
                Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
            }
            "call-error", "file-error" -> Toast.makeText(this, event.optString("message"), Toast.LENGTH_LONG).show()
            "file-sent" -> Toast.makeText(this, "Sent ${event.optString("name")}", Toast.LENGTH_SHORT).show()
            "file-progress" -> { if (event.optInt("received") == event.optInt("total")) Toast.makeText(this, "Receiving ${event.optString("name")}?", Toast.LENGTH_SHORT).show() }
        }
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(enabled: Boolean) {
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isSpeakerphoneOn = enabled
    }

    private var callMuted = false
    private var cameraEnabled = true
    private var speakerEnabled = true

    private fun sendPickedFile(uri: Uri) {
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else "shared-file"
            } ?: "shared-file"
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > 90 * 1024 * 1024) throw IllegalArgumentException("Maximum file size is 90 MB"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IllegalStateException("Could not open file")
            if (!webRtcClient.sendFile(name, mime, bytes)) Toast.makeText(this, "File could not be sent. Check the connection.", Toast.LENGTH_LONG).show()
            else addAttachmentBubble(name, mime, bytes, outgoing = true, privatePath = null)
        } catch (e: Exception) { Toast.makeText(this, "File error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun addAttachmentBubble(name: String, mime: String, bytes: ByteArray, outgoing: Boolean, privatePath: String?) {
        val container = messagesContainer
        if (!chatScreenOpen || container == null) {
            // Keep attachment content private; do not publish it to Gallery/Downloads.
            pendingChatMessages.add((if (outgoing) "You sent a file: " else "Received a file: ") + name to outgoing)
            return
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(if (outgoing) 0xFF29234F.toInt() else 0xFF263247.toInt())
        }
        if (mime.startsWith("image/")) {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                bubble.addView(ImageView(this).apply {
                    setImageBitmap(bitmap); adjustViewBounds = true; maxHeight = 240
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "Image attachment $name"
                }, LinearLayout.LayoutParams(240, 200))
            }
        } else {
            bubble.addView(TextView(this).apply {
                text = if (mime.startsWith("application/pdf")) "?  PDF" else "?  File"
                textSize = 18f; setTextColor(0xFFF8FAFC.toInt())
            })
        }
        bubble.addView(TextView(this).apply {
            text = "$name\n${if (bytes.size < 1024*1024) "${bytes.size / 1024} KB" else "${bytes.size / (1024*1024)} MB"}"
            textSize = 13f; setTextColor(0xFFE2E8F0.toInt()); setPadding(0, 8, 0, 4)
        })
        if (!outgoing && privatePath != null) {
            bubble.addView(android.widget.Button(this).apply {
                text = "Save to device"; isAllCaps = false
                setOnClickListener {
                    try {
                        pendingReceivedFile = Triple(name, mime, java.io.File(privatePath).readBytes())
                        saveReceivedFile.launch(name)
                    } catch (e: Exception) { Toast.makeText(this@MainActivity, "Could not open attachment", Toast.LENGTH_LONG).show() }
                }
            })
        } else {
            bubble.addView(TextView(this).apply { text = if (outgoing) "Sent" else "Received"; textSize = 11f; setTextColor(0xFF94A3B8.toInt()) })
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 12; gravity = if (outgoing) Gravity.END else Gravity.START
            width = (resources.displayMetrics.widthPixels * 0.68f).toInt()
        }
        container.addView(bubble, params)
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendChatMessage(text: String, outgoing: Boolean) {
        val container = messagesContainer
        if (!chatScreenOpen || container == null) {
            pendingChatMessages.add(text to outgoing)
            return
        }

        val bubble = TextView(this).apply {
            this.text = if (outgoing) "You\n$text" else "Peer\n$text"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            setBackgroundColor(if (outgoing) 0xFF51439A.toInt() else 0xFF263247.toInt())
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12
            gravity = if (outgoing) Gravity.END else Gravity.START
        }
        container.addView(bubble, params)
        chatScrollView?.post { chatScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearChatViewReferences() {
        messagesContainer = null
        chatScrollView = null
        messageInput = null
        chatPeerStatus = null
        remoteVideoRenderer = null
        localVideoRenderer = null
        videoPanel = null
        chatScrollContainer = null
        chatFeatureButtons = null
        chatComposer = null
    }

    private fun showPairingError(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleGhostLinkBack() {
        if (standaloneScreen) {
            if (chatScreenOpen) {
                chatScreenOpen = false
                clearChatViewReferences()
                // Back from a conversation always returns to the in-app Chats list.
                selectedTab = Tab.CHATS
            }
            restoreMainScreen()
            return
        }

        // Do not send the user to the launcher from the active conversation flow.
        // From other main tabs, Back takes them to Chats first. A second Back from Chats
        // backgrounds the app without finishing the Activity or closing WebRTC.
        if (selectedTab != Tab.CHATS) {
            showTab(Tab.CHATS)
        } else {
            moveTaskToBack(true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Compatibility fallback for Android versions that still call this override.
        handleGhostLinkBack()
    }

    private fun showComingSoon(feature: String) {
        Toast.makeText(
            this,
            "$feature will be connected in the next step.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        // Back now backgrounds the task instead of finishing it. Only explicitly finish/close
        // the Activity's transport when it is actually finishing, not when merely backgrounded.
        if (isFinishing && ::webRtcClient.isInitialized) {
            webRtcClient.close()
        }
        super.onDestroy()
    }
}