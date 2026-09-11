package com.example.karooinsta360

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE client for the Insta360 "Architecture B" (Direct Control) protocol,
 * scoped to just starting and stopping video recording on the Ace Pro.
 *
 * The camera acts as a GATT SERVER; this class connects as a client,
 * writes commands to BE81, and listens for responses/notifications on BE82.
 *
 * Wire format used here is "Header16" (X3 / ONE RS / Ace Pro family).
 * NOTE: this does NOT implement the Go2BlePacket (5-byte header + CRC-16)
 * variant used by GO 2/3/3S — not needed for the Ace Pro.
 */
class Insta360BleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        /**
         * @param responseCode the code in the inbound frame. For this protocol that is an
         *   HTTP-like *status*, not an echo of what was asked — 200 (0xC8) for OK, 500 for
         *   error — so it identifies nothing about which request this answers.
         * @param requestCode the command code originally sent under this sequence number,
         *   or -1 if unknown. This is the only way to tell a capture-status reply from a
         *   battery reply; matching on responseCode can never work.
         */
        fun onCommandResponse(responseCode: Int, requestCode: Int, sequence: Int, payload: ByteArray)
        fun onNotification(notificationCode: Int, payload: ByteArray)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "Insta360Ble"

        val SERVICE_BE80: UUID = shortUuid("BE80")
        val CHAR_BE81_WRITE: UUID = shortUuid("BE81")   // app -> camera
        val CHAR_BE82_NOTIFY: UUID = shortUuid("BE82")  // camera -> app
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Insta360 official command codes — only what's needed to start/stop recording.
        const val CMD_START_CAPTURE = 4
        const val CMD_STOP_CAPTURE = 5

        // Authorization/pairing commands (see doc/ble_protocol.md section 5 in
        // xaionaro-go/insta360ctl). Added to test whether this camera silently
        // drops capture commands because the connection isn't authorized yet —
        // reference project's own code only runs this handshake for GO2/GO3, but
        // its docs describe the requirement as general, and it's untested there
        // against an Ace Pro/Ace Pro 2.
        const val CMD_CHECK_AUTHORIZATION = 39   // 0x27
        const val CMD_REQUEST_AUTHORIZATION = 86 // 0x56

        /**
         * **Added (2026-09-07)** — asks the camera what it is currently doing.
         *
         * Notifications (below) only report *changes*, so on every connect and reconnect
         * this is what tells us whether the camera was already rolling before we got
         * there. Without it, a camera started by its own shutter button before the Karoo
         * finished connecting looks idle to this app forever.
         */
        const val CMD_GET_CURRENT_CAPTURE_STATUS = 15 // 0x0F

        /**
         * PHONE_COMMAND_GET_OPTIONS. The request body is a `GetOptions` message whose
         * field 1 is a repeated `OptionType`; the reply is `GetOptionsResp`, field 2 of
         * which is an `Options` message.
         */
        const val CMD_GET_OPTIONS = 8

        /** `OptionType.BATTERY_STATUS`. */
        const val OPTION_TYPE_BATTERY_STATUS = 11

        /**
         * **Added (2026-09-07)** — unsolicited notification codes the camera pushes on
         * BE82 without being asked, catalogued from `pkg/protocol/messagecode` in
         * xaionaro-go/insta360ctl. [handleIncoming] already separates these from command
         * responses (sequence 0 with the from-camera flag set); until now they were only
         * logged.
         *
         * These are what make external recording detectable: the physical shutter button
         * on the camera, a paired Insta360 remote, or the camera stopping itself.
         */
        const val NOTIFY_CAPTURE_AUTO_SPLIT = 0x2002
        const val NOTIFY_BATTERY_UPDATE = 0x2003
        const val NOTIFY_BATTERY_LOW = 0x2004
        const val NOTIFY_SHUTDOWN = 0x2005
        const val NOTIFY_STORAGE_UPDATE = 0x2006
        const val NOTIFY_STORAGE_FULL = 0x2007
        const val NOTIFY_KEY_PRESSED = 0x2008
        const val NOTIFY_CAPTURE_STOPPED = 0x2009
        const val NOTIFY_CURRENT_CAPTURE_STATUS = 0x2010
        const val NOTIFY_SYNC_CAPTURE_BUTTON_TRIGGER = 0x2014

        /**
         * **Added (2026-09-07, fix)** — every notification code in the Insta360 protocol
         * lives at 0x2000 and above, while every command code is well below it (start
         * capture is 4, authorization 0x27/0x56). See [handleIncoming] for why the code
         * range, rather than the sequence number, is what now decides whether an inbound
         * frame is a notification.
         */
        const val NOTIFY_CODE_FLOOR = 0x2000

        /**
         * How close together two byte-identical frames must be to count as one delivery
         * duplicated across characteristics rather than two real events. The observed
         * duplicates land 1-13ms apart; 200ms is far enough beyond that to be safe while
         * staying well under any interval at which the camera repeats real information.
         */
        private const val DUPLICATE_WINDOW_MS = 200L

        /** Sequence numbers wrap at 254, so this only has to outlive a few round trips. */
        private const val MAX_PENDING_REQUESTS = 32

        /** Matches insta360ctl, which sets 517 before subscribing. */
        const val REQUESTED_MTU = 517
        const val DEFAULT_MTU = 23

        // CheckAuthorization.InitiatorType (protobuf enum, authorization.proto).
        private const val INITIATOR_TYPE_APP = 2

        // AuthorizationOperationType (protobuf enum, authorization.proto).
        private const val AUTHORIZATION_OPERATION_PAIR = 1

        /** Insta360 uses the vendor-specific 16-bit UUID pattern; expand to full 128-bit form. */
        private fun shortUuid(hex4: String): UUID =
            UUID.fromString("0000$hex4-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var deviceAddress: String = ""
    private val seq = AtomicInteger(1)

    /**
     * Last frame seen, for the duplicate filter in [handleIncoming]. Guarded by [dupeLock]
     * because GATT callbacks arrive on more than one binder thread — the duplicates
     * themselves were observed landing on different threads a millisecond or two apart.
     */
    private val dupeLock = Any()
    private var lastFrameHash: Int? = null
    private var lastFrameSource: String? = null
    private var lastFrameAt: Long = 0L

    // Reassembly buffer for fragmented responses (Header16 supports multi-fragment payloads).
    private var reassemblyBuffer: ByteArray? = null
    private var reassemblyCommandCode: Int = 0
    private var reassemblySeq: Int = 0

    /**
     * **Added (2026-09-07, fix)** — CCCD writes still to be issued, and whether
     * [Listener.onConnected] has already fired.
     *
     * Android's GATT stack processes exactly one descriptor write at a time and silently
     * drops any issued while another is in flight, so subscribing to several notify
     * characteristics means writing them one at a time, each from the previous one's
     * completion callback.
     */
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
    private var connectedAnnounced = false
    private var currentMtu = DEFAULT_MTU

    /**
     * **Added (2026-09-07)** — frames waiting to be written, and whether one is in flight.
     *
     * Android's GATT stack permits exactly one outstanding operation per connection:
     * writeCharacteristic returns false if another is still in progress, and the frame is
     * simply lost. The connect sequence exposed this immediately — CheckAuthorization
     * followed 18ms later by GetCurrentCaptureStatus, and the second write was refused.
     * Any two commands issued close together hit the same problem, which for this app
     * means a trigger firing while another command is in flight silently does nothing.
     */
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    fun connect(device: BluetoothDevice) {
        Log.i(TAG, "connect(): device=${device.address} sdk=${android.os.Build.VERSION.SDK_INT}")
        deviceAddress = device.address
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        synchronized(pendingWrites) {
            pendingWrites.clear()
            writeInFlight = false
        }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed: $status")
                return
            }
            val service = g.getService(SERVICE_BE80)
            if (service == null) {
                listener.onError("BE80 service not found on device")
                return
            }
            writeChar = service.getCharacteristic(CHAR_BE81_WRITE)
            if (writeChar == null) {
                listener.onError("BE81 write characteristic not found")
                return
            }
            Log.i(TAG, "BE81 properties=0x${writeChar!!.properties.toString(16)} " +
                "(WRITE=0x08, WRITE_NO_RESPONSE=0x04) writeType currently used=${writeChar!!.writeType}")

            // **Fixed (2026-09-07)** — this used to subscribe to BE82 and nothing else,
            // which is why camera-side recording was never detected even after the frame
            // routing was corrected: insta360ctl subscribes to five notify
            // characteristics (BE82, AE02, and B002/B003/B004 on the secondary service),
            // and if this camera pushes capture status on any of the others, the frames
            // were never arriving at all.
            //
            // Rather than hard-coding that list and hoping it matches this model, every
            // characteristic on every service that advertises NOTIFY or INDICATE is
            // subscribed to. Full discovery is also logged, so if something still doesn't
            // arrive, the log shows exactly what the camera does and doesn't offer.
            connectedAnnounced = false
            pendingSubscriptions.clear()
            g.services.forEach { svc ->
                svc.characteristics.forEach { ch ->
                    val props = ch.properties
                    val notifies = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    val indicates = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    Log.i(
                        TAG,
                        "Discovered ${svc.uuid} / ${ch.uuid} props=0x${props.toString(16)}" +
                            (if (notifies || indicates) " [subscribing]" else ""),
                    )
                    if (notifies || indicates) pendingSubscriptions.addLast(ch)
                }
            }

            if (pendingSubscriptions.isEmpty()) {
                Log.w(TAG, "No notify characteristics found — camera-side events will not arrive")
                announceConnected()
                return
            }

            // **Added (2026-09-07)** — negotiate a larger ATT MTU before subscribing.
            //
            // insta360ctl's documented sequence is: connect, discover services, set MTU to
            // 517, subscribe, then authorize. We were skipping the MTU step entirely and
            // running at the 23-byte default, i.e. 20 usable bytes per packet. Two
            // consequences, both matching what the logs showed: our 37-byte
            // CheckAuthorization frame had to go out as an Android queued long write,
            // which peripherals often reject silently, and — more importantly — any
            // response longer than 20 bytes simply cannot be sent back to us. A camera
            // that accepts every write and answers none is exactly what that produces.
            //
            // Subscriptions are deferred to onMtuChanged so they happen on the negotiated
            // link rather than the default one.
            if (!g.requestMtu(REQUESTED_MTU)) {
                Log.w(TAG, "requestMtu($REQUESTED_MTU) refused — subscribing at default MTU")
                subscribeNext(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Proceed regardless of status: a refused MTU bump leaves us where we already
            // were, which is worth trying rather than aborting the connection over.
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status (usable payload=${mtu - 3})")
            currentMtu = mtu
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Not fatal on its own: one characteristic refusing a subscription
                // shouldn't stop the others, and the important one may well be later in
                // the queue.
                Log.w(TAG, "CCCD write failed for ${descriptor.characteristic?.uuid} status=$status")
            }
            if (pendingSubscriptions.isEmpty()) announceConnected() else subscribeNext(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Write to ${characteristic.uuid} succeeded")
            } else {
                Log.e(TAG, "Write to ${characteristic.uuid} FAILED, status=$status")
                listener.onError("BLE write failed, status=$status")
            }
            // Link is free again — send whatever queued up behind this one.
            synchronized(pendingWrites) { writeInFlight = false }
            pumpWrites()
        }

        /**
         * Pre-API-33 notification callback.
         *
         * **(2026-09-07)** This was the only overload implemented, which is very likely
         * why no inbound frame — not a command response, not a notification — was ever
         * reaching the app: Android 13 added the three-argument form below, and the
         * framework calls that one on API 33+. With `targetSdk = 34` on a Karoo running
         * Android 13 or later, this method may simply never fire, leaving writes working
         * (they take a different path) and the entire receive side silently dead.
         *
         * Both overloads are implemented now and route to the same place. Whichever the
         * platform calls, the frame gets handled; the log line in [handleIncoming] records
         * which one it was.
         */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            Log.i(TAG, "onCharacteristicChanged (legacy overload) from ${characteristic.uuid}")
            handleIncoming(data, characteristic.uuid.toString())
        }

        /**
         * API 33+ notification callback. See the deprecated overload above.
         *
         * Was filtered to BE82 only before today; now accepts frames from every
         * characteristic we subscribed to, since which one carries capture status on this
         * model is exactly what's unknown.
         */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.i(TAG, "onCharacteristicChanged (API33 overload) from ${characteristic.uuid}")
            handleIncoming(value, characteristic.uuid.toString())
        }
    }

    /** See [pendingSubscriptions] for why these are issued one at a time. */
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = pendingSubscriptions.removeFirstOrNull() ?: run {
            announceConnected()
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // Some stacks auto-configure CCCD. Nothing to wait for, so move straight on.
            Log.w(TAG, "CCCD descriptor missing on ${ch.uuid}")
            subscribeNext(g)
            return
        }
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(cccd)
    }

    private fun announceConnected() {
        if (connectedAnnounced) return
        connectedAnnounced = true
        listener.onConnected()
    }

    /**
     * Send a Header16-framed command to the camera.
     *
     * Header16 layout (16 bytes) + optional protobuf payload:
     *   0-3   total inner size, i.e. 16 + payload length (uint32 LE, header INCLUDED)
     *   4     mode (0x04 = Message)
     *   5-6   reserved (0x00)
     *   7-8   command code (LE)
     *   9     content type (0x02 = protobuf)
     *   10    sequence number (1-254)
     *   11-12 reserved (0x00)
     *   13    flags: bit7 = is_last_fragment, bit6 = direction (0 = app->cam)
     *   14-15 reserved (0x00)
     *
     * CONFIRMED against a real BLE HCI snoop capture of the official Insta360
     * app talking to this camera (an Ace Pro 2): offset 0 is a 4-byte LE
     * "total_inner_size" = 16 + payload_length, NOT a 2-byte payload-length
     * with offset 2-3 reserved as the reference project's docs describe for
     * "Header16" cameras. Every one of 6 captured commands (GetOptions x4,
     * CheckAuthorization, and one unidentified cmd=225) had this field exactly
     * equal to 16 + its payload size. Our previous encoding wrote 0 into this
     * field for every empty-payload command (START_CAPTURE, STOP_CAPTURE,
     * CHECK_AUTHORIZATION) — declaring the frame as zero bytes total — which
     * is almost certainly why the camera silently discarded every command we
     * sent regardless of write type or command code.
     */
    fun sendCommand(commandCode: Int, protobufPayload: ByteArray = ByteArray(0)): Int {
        Log.i(TAG, "sendCommand called: commandCode=$commandCode payloadLen=${protobufPayload.size}")

        val g = gatt ?: run {
            val msg = "sendCommand failed: no active GATT connection"
            Log.e(TAG, msg)
            listener.onError(msg)
            return -1
        }
        val char = writeChar ?: run {
            val msg = "sendCommand failed: write characteristic (BE81) not resolved"
            Log.e(TAG, msg)
            listener.onError(msg)
            return -1
        }
        val sequence = nextSeq()
        rememberRequest(sequence, commandCode)
        val header = ByteArray(16)
        writeU32LE(header, 0, 16 + protobufPayload.size) // total_inner_size, header included
        header[4] = 0x04
        writeU16LE(header, 7, commandCode)
        header[9] = 0x02
        header[10] = sequence.toByte()
        header[13] = (0x80 or 0x00).toByte() // last fragment, direction app->cam

        val frame = header + protobufPayload
        Log.i(TAG, "Writing frame (seq=$sequence): ${frame.joinToString(" ") { "%02X".format(it) }}")

        // NOTE (write type): on this specific camera (an "Ace Pro 2"), BE81's advertised GATT
        // properties are Read(0x02)+Write(0x08) only — it does NOT advertise
        // WriteNR(0x04). WRITE_TYPE_DEFAULT (write WITH response) is therefore the
        // only mode this characteristic actually declares support for; forcing
        // WRITE_TYPE_NO_RESPONSE (as tried earlier, reasoning from insta360ctl's
        // GO2/GO3 code path) reports GATT_SUCCESS locally but the resulting ATT
        // "Write Command" is not a declared-supported operation on this attribute,
        // so it's likely silently dropped by the camera's BLE stack. Reverted.
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        // Queued rather than written straight out: see [pendingWrites]. Android permits
        // one outstanding GATT operation, so issuing a second command before the first
        // completes loses it silently.
        enqueueWrite(frame)
        return sequence
    }

    /** Adds a frame to the write queue and starts it if the link is idle. */
    private fun enqueueWrite(frame: ByteArray) {
        synchronized(pendingWrites) {
            pendingWrites.addLast(frame)
            if (writeInFlight) {
                Log.i(TAG, "Write queued behind one in flight (depth=${pendingWrites.size})")
                return
            }
        }
        pumpWrites()
    }

    private fun pumpWrites() {
        val frame = synchronized(pendingWrites) {
            if (writeInFlight) return
            val next = pendingWrites.removeFirstOrNull() ?: return
            writeInFlight = true
            next
        }

        val g = gatt
        val char = writeChar
        if (g == null || char == null) {
            synchronized(pendingWrites) { writeInFlight = false }
            listener.onError("write dropped: no active GATT connection")
            return
        }

        if (frame.size > currentMtu - 3) {
            // Not fatal — Android will fall back to a queued long write — but worth
            // recording, since a peripheral rejecting long writes is invisible otherwise.
            Log.w(TAG, "Frame is ${frame.size}B, larger than MTU-3 (${currentMtu - 3}) — long write")
        }

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        char.value = frame
        @Suppress("DEPRECATION")
        val initiated = g.writeCharacteristic(char)
        Log.i(TAG, "writeCharacteristic() call returned: $initiated")
        if (!initiated) {
            synchronized(pendingWrites) { writeInFlight = false }
            listener.onError("writeCharacteristic() returned false — write not queued")
            pumpWrites()
        }
    }

    // Recording control.
    fun startCapture(): Int {
        Log.i(TAG, "startCapture() called")
        return sendCommand(CMD_START_CAPTURE)
    }

    fun stopCapture(): Int {
        Log.i(TAG, "stopCapture() called")
        return sendCommand(CMD_STOP_CAPTURE)
    }

    /**
     * **Added (2026-09-07)** — see [CMD_GET_CURRENT_CAPTURE_STATUS]. Sent on connect so a
     * recording already in progress is picked up rather than assumed away.
     *
     * The response arrives through [Listener.onCommandResponse] with this command code;
     * [com.example.karooinsta360.connection.Insta360ConnectionManager] parses it there.
     */
    fun queryCaptureStatus(): Int {
        Log.i(TAG, "queryCaptureStatus() called")
        return sendCommand(CMD_GET_CURRENT_CAPTURE_STATUS)
    }

    // Authorization/pairing.
    //
    // CheckAuthorization protobuf message (field numbers from authorization.proto
    // in xaionaro-go/insta360ctl):
    //   field 1 (string):  authorization_id  — identifies this app/device pairing
    //   field 2 (string):  findmy_token      — optional, omitted here
    //   field 3 (varint):  initiator_type    — who initiated the check (APP = 2)
    /**
     * Ask for the battery level.
     *
     * The camera does NOT push this. 0x2003 (NOTIFY_BATTERY_UPDATE) exists in the code
     * table but an Ace Pro 2 never sends it — a logcat over several minutes shows it
     * pushing 0x2017, 0x201b, 0x203a, 0x203c, 0x2040 and 0x206a, none of which carries
     * anything in a percentage range. The level has to be requested.
     *
     * Body is `GetOptions { option_types: [BATTERY_STATUS] }`, i.e. the two bytes 08 0B.
     * The reply nests the answer two messages deep — see
     * `Insta360ConnectionManager.handleBatteryResponse`.
     */
    fun queryBatteryStatus(): Int {
        Log.i(TAG, "queryBatteryStatus() called")
        val payload = ProtoWriter()
            .varintField(1, OPTION_TYPE_BATTERY_STATUS)
            .toByteArray()
        return sendCommand(CMD_GET_OPTIONS, payload)
    }

    fun checkAuthorization(): Int {
        Log.i(TAG, "checkAuthorization() called, authorization_id=$deviceAddress")
        val payload = ProtoWriter()
            .stringField(1, deviceAddress)
            .varintField(3, INITIATOR_TYPE_APP)
            .toByteArray()
        return sendCommand(CMD_CHECK_AUTHORIZATION, payload)
    }

    // RequestAuthorization protobuf message:
    //   field 1 (varint): operation_type — PAIR = 1
    // Triggers a physical confirmation prompt on the camera itself.
    fun requestAuthorization(): Int {
        Log.i(TAG, "requestAuthorization() called — camera may require confirming a prompt on-device")
        val payload = ProtoWriter()
            .varintField(1, AUTHORIZATION_OPERATION_PAIR)
            .toByteArray()
        return sendCommand(CMD_REQUEST_AUTHORIZATION, payload)
    }

    /** Minimal hand-rolled protobuf (proto3) wire-format writer — just enough for the
     *  authorization messages above, without pulling in a full protobuf dependency. */
    private class ProtoWriter {
        private val out = java.io.ByteArrayOutputStream()

        fun varintField(fieldNumber: Int, value: Int): ProtoWriter {
            out.write((fieldNumber shl 3) or 0) // wire type 0 = varint
            writeVarint(value)
            return this
        }

        fun stringField(fieldNumber: Int, value: String): ProtoWriter {
            if (value.isEmpty()) return this // proto3: omit default/empty values
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write((fieldNumber shl 3) or 2) // wire type 2 = length-delimited
            writeVarint(bytes.size)
            out.write(bytes)
            return this
        }

        private fun writeVarint(valueIn: Int) {
            var value = valueIn
            while (true) {
                if (value and 0x7F.inv() == 0) {
                    out.write(value)
                    return
                }
                out.write((value and 0x7F) or 0x80)
                value = value ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /**
     * True when this frame has already been handled, arriving a second time on a different
     * characteristic.
     *
     * We subscribe to every characteristic that advertises NOTIFY (see the subscription
     * code above) because which one carries capture status on a given model is exactly what
     * isn't documented. The cost is that the camera pushes some frames on more than one of
     * them, and every one was being processed twice — visible in logcat as each
     * notification logged twice, a millisecond or two apart, from different threads.
     *
     * Mostly that was wasted work, since the handlers are idempotent. Not entirely, though:
     * a duplicate *fragment* of a multi-fragment response was being appended to
     * [reassemblyBuffer] a second time, silently corrupting the reassembled payload. So
     * this filter runs before anything else touches the frame.
     *
     * Matching is on exact content within [DUPLICATE_WINDOW_MS]. Frames that genuinely
     * repeat identical content carry a sequence number that differs (responses) or say
     * nothing new (a status push repeating the state we just recorded), so dropping a
     * byte-identical repeat inside a fifth of a second costs nothing either way.
     */
    private fun isDuplicateFrame(data: ByteArray, sourceUuid: String): Boolean {
        val hash = data.contentHashCode()
        val now = System.currentTimeMillis()
        synchronized(dupeLock) {
            val elapsed = now - lastFrameAt
            if (hash == lastFrameHash && elapsed < DUPLICATE_WINDOW_MS) {
                Log.d(
                    TAG,
                    "Duplicate frame from $sourceUuid (already handled from $lastFrameSource " +
                        "${elapsed}ms ago) — ignoring",
                )
                return true
            }
            lastFrameHash = hash
            lastFrameSource = sourceUuid
            lastFrameAt = now
        }
        return false
    }

    private fun handleIncoming(data: ByteArray, sourceUuid: String = CHAR_BE82_NOTIFY.toString()) {
        if (isDuplicateFrame(data, sourceUuid)) return

        if (data.size < 16) {
            // Confirmed benign: the camera sends short (7-byte) periodic
            // heartbeat/keepalive notifications on BE82 outside the normal
            // 16-byte header framing (observed roughly once per second in a
            // real BLE capture of the official app's session). Not an error.
            Log.i(TAG, "Short frame from $sourceUuid (${data.size} bytes), raw=${data.joinToString(" ") { "%02X".format(it) }} — likely a heartbeat, ignoring")
            return
        }
        val totalInnerSize = readU32LE(data, 0)
        val payloadLen = (totalInnerSize - 16).coerceAtLeast(0)
        val commandCode = readU16LE(data, 7)
        val sequence = data[10].toInt() and 0xFF
        val flags = data[13].toInt() and 0xFF
        val isLastFragment = (flags and 0x80) != 0
        val fromCamera = (flags and 0x40) != 0

        val fragment = if (data.size >= 16 + payloadLen) data.copyOfRange(16, 16 + payloadLen) else ByteArray(0)

        val combined = if (reassemblyBuffer != null && reassemblyCommandCode == commandCode && reassemblySeq == sequence) {
            reassemblyBuffer!! + fragment
        } else {
            fragment
        }

        if (!isLastFragment) {
            reassemblyBuffer = combined
            reassemblyCommandCode = commandCode
            reassemblySeq = sequence
            return
        }

        reassemblyBuffer = null

        // Log every inbound frame from the camera, decoded or not. Camera-side recording
        // detection is the one feature here that depends entirely on frames we did not
        // ask for, so "nothing happened" needs to be distinguishable from "nothing
        // arrived" without attaching a BLE sniffer.
        if (fromCamera) {
            Log.i(
                TAG,
                "RX $sourceUuid cmd=0x${commandCode.toString(16)} seq=$sequence len=${combined.size} " +
                    "raw=${combined.joinToString(" ") { "%02X".format(it) }}",
            )
        }

        // **Fixed (2026-09-07)** — this used to require `sequence == 0` to treat a frame
        // as an unsolicited notification, which is why manually starting a recording on
        // the camera was never noticed: insta360ctl identifies unsolicited frames by
        // seq == 255 *or* by there being no pending request for that sequence, not by
        // seq == 0, so anything the Ace Pro 2 pushed with a non-zero sequence was being
        // handed to onCommandResponse and quietly dropped.
        //
        // Deciding by code range instead is unambiguous in both directions: notification
        // codes start at 0x2000 and command codes never reach it, so no legitimate
        // command response can be mistaken for a notification regardless of what
        // sequence numbering the camera uses.
        // Note this no longer requires the from-camera flag either: if a model doesn't set
        // bit 0x40 on its pushes, requiring it would drop them for the same reason the
        // sequence check did.
        val isNotification = commandCode >= NOTIFY_CODE_FLOOR
        if (isNotification || (fromCamera && sequence == 0)) {
            listener.onNotification(commandCode, combined)
        } else {
            listener.onCommandResponse(commandCode, takeRequest(sequence), sequence, combined)
        }
    }

    /**
     * Sequence number -> the command code we sent under it, so a reply can be matched to
     * its request. Bounded and evicted oldest-first: a command whose reply never arrives
     * must not pin an entry forever.
     */
    private val pendingRequests = object : LinkedHashMap<Int, Int>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean =
            size > MAX_PENDING_REQUESTS
    }

    @Synchronized
    private fun rememberRequest(sequence: Int, commandCode: Int) {
        pendingRequests[sequence] = commandCode
    }

    /** Consumes and returns the command code sent under [sequence], or -1 if unknown. */
    @Synchronized
    private fun takeRequest(sequence: Int): Int = pendingRequests.remove(sequence) ?: -1

    private fun nextSeq(): Int {
        val s = seq.getAndUpdate { if (it >= 254) 1 else it + 1 }
        return s
    }

    private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun readU16LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeU32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readU32LE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }
}
