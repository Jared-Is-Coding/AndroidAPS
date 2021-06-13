package info.nightscout.androidaps.plugins.pump.insight.app_layer

import info.nightscout.androidaps.plugins.pump.insight.app_layer.Service.Companion.fromId
import info.nightscout.androidaps.plugins.pump.insight.descriptors.AppCommands
import info.nightscout.androidaps.plugins.pump.insight.descriptors.AppCommands.Companion.fromType
import info.nightscout.androidaps.plugins.pump.insight.descriptors.AppErrors
import info.nightscout.androidaps.plugins.pump.insight.descriptors.MessagePriority
import info.nightscout.androidaps.plugins.pump.insight.exceptions.IncompatibleAppVersionException
import info.nightscout.androidaps.plugins.pump.insight.exceptions.InvalidAppCRCException
import info.nightscout.androidaps.plugins.pump.insight.exceptions.UnknownServiceException
import info.nightscout.androidaps.plugins.pump.insight.exceptions.app_layer_errors.UnknownAppLayerErrorCodeException
import info.nightscout.androidaps.plugins.pump.insight.satl.DataMessage
import info.nightscout.androidaps.plugins.pump.insight.utils.ByteBuf
import info.nightscout.androidaps.plugins.pump.insight.utils.crypto.Cryptograph

open class AppLayerMessage(private val messagePriority: MessagePriority, private val inCRC: Boolean, private val outCRC: Boolean, open var service: Service?) : Comparable<AppLayerMessage> {

    protected open val data: ByteBuf
        get() = ByteBuf(0)

    @Throws(Exception::class)
    protected open fun parse(byteBuf: ByteBuf?) = Unit

    fun serialize(clazz: Class<out AppLayerMessage?>?): ByteBuf {
        val data = data.bytes
        val byteBuf = ByteBuf(4 + data.size + if (outCRC) 2 else 0)
        byteBuf.putByte(VERSION)
        byteBuf.putByte(service!!.id)
        byteBuf.putUInt16LE(fromType(clazz!!)!!.id)
        byteBuf.putBytes(data)
        if (outCRC) byteBuf.putUInt16LE(Cryptograph.calculateCRC(data))
        return byteBuf
    }

    override fun compareTo(other: AppLayerMessage): Int {
        return messagePriority.compareTo(other.messagePriority)
    }

    companion object {

        private const val VERSION: Byte = 0x20
        @Throws(Exception::class) fun deserialize(byteBuf: ByteBuf): AppLayerMessage {
            val version = byteBuf.readByte()
            val service = byteBuf.readByte()
            val command = byteBuf.readUInt16LE()
            val error = byteBuf.readUInt16LE()
            val clazz = AppCommands.fromId(command)!!.type
            if (version != VERSION) throw IncompatibleAppVersionException()
            val message = clazz.newInstance()
            if (fromId(service) == null) throw UnknownServiceException()
            if (error != 0) {
                val exceptionClass = AppErrors.fromId(error)?.type
                if (exceptionClass == null) throw UnknownAppLayerErrorCodeException(error) else throw exceptionClass.getConstructor(Int::class.javaPrimitiveType).newInstance(error)!!
            }
            val data = byteBuf.readBytes(byteBuf.filledSize - if (message!!.inCRC) 2 else 0)
            if (message.inCRC && Cryptograph.calculateCRC(data) != byteBuf.readUInt16LE()) throw InvalidAppCRCException()
            message.parse(ByteBuf.from(data))
            return message
        }

        @JvmStatic fun wrap(message: AppLayerMessage): DataMessage {
            val dataMessage = DataMessage()
            dataMessage.data = message.serialize(message.javaClass)
            return dataMessage
        }

        @JvmStatic @Throws(Exception::class) fun unwrap(dataMessage: DataMessage): AppLayerMessage? {
            return dataMessage.data?.let { deserialize(it) }
        }
    }
}