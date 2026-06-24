package io.github.kclip.core.domain

/**
 * Phase 2 の credential 派生と state checksum に使う SHA-256 実装。
 */
internal object Sha256 {
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff
    private const val CHUNK_BYTES = 64
    private const val HASH_BYTES = 32
    private const val INITIAL_SCHEDULE_WORDS = 16
    private const val INT_BYTES = 4
    private const val LENGTH_BYTES = 8
    private const val MESSAGE_SCHEDULE_WORDS = 64
    private const val ONE_BIT_BYTE_COUNT = 1
    private const val FIRST_PADDING_BYTE = 0x80.toByte()

    private val INITIAL_HASHES = intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )

    private val ROUND_CONSTANTS = intArrayOf(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf.toInt(),
        0xe9b5dba5.toInt(),
        0x3956c25b,
        0x59f111f1,
        0x923f82a4.toInt(),
        0xab1c5ed5.toInt(),
        0xd807aa98.toInt(),
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe.toInt(),
        0x9bdc06a7.toInt(),
        0xc19bf174.toInt(),
        0xe49b69c1.toInt(),
        0xefbe4786.toInt(),
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152.toInt(),
        0xa831c66d.toInt(),
        0xb00327c8.toInt(),
        0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(),
        0xd5a79147.toInt(),
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e.toInt(),
        0x92722c85.toInt(),
        0xa2bfe8a1.toInt(),
        0xa81a664b.toInt(),
        0xc24b8b70.toInt(),
        0xc76c51a3.toInt(),
        0xd192e819.toInt(),
        0xd6990624.toInt(),
        0xf40e3585.toInt(),
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814.toInt(),
        0x8cc70208.toInt(),
        0x90befffa.toInt(),
        0xa4506ceb.toInt(),
        0xbef9a3f7.toInt(),
        0xc67178f2.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        val paddedInput = padInput(input)
        val hashValues = INITIAL_HASHES.copyOf()
        val messageSchedule = IntArray(size = MESSAGE_SCHEDULE_WORDS)

        var chunkOffset = 0
        while (chunkOffset < paddedInput.size) {
            fillInitialScheduleWords(paddedInput, chunkOffset, messageSchedule)
            fillExtendedScheduleWords(messageSchedule)
            compressChunk(messageSchedule, hashValues)

            chunkOffset += CHUNK_BYTES
        }

        return hashValuesToBytes(hashValues)
    }

    private fun padInput(input: ByteArray): ByteArray {
        val inputBitLength = input.size.toLong() * BITS_PER_BYTE
        val zeroPaddingBytes = calculateZeroPaddingBytes(input.size)
        val paddedInput = ByteArray(input.size + ONE_BIT_BYTE_COUNT + zeroPaddingBytes + LENGTH_BYTES)
        input.copyInto(paddedInput)
        paddedInput[input.size] = FIRST_PADDING_BYTE
        writeLongBigEndian(paddedInput, paddedInput.size - LENGTH_BYTES, inputBitLength)

        return paddedInput
    }

    private fun calculateZeroPaddingBytes(inputSize: Int): Int {
        val sizeWithOneBit = inputSize + ONE_BIT_BYTE_COUNT
        val bytesBeforeLength = sizeWithOneBit % CHUNK_BYTES
        val targetBytesBeforeLength = CHUNK_BYTES - LENGTH_BYTES
        if (bytesBeforeLength <= targetBytesBeforeLength) {
            return targetBytesBeforeLength - bytesBeforeLength
        }

        return CHUNK_BYTES + targetBytesBeforeLength - bytesBeforeLength
    }

    private fun fillInitialScheduleWords(input: ByteArray, chunkOffset: Int, schedule: IntArray) {
        for (wordIndex in 0 until INITIAL_SCHEDULE_WORDS) {
            val offset = chunkOffset + wordIndex * INT_BYTES
            schedule[wordIndex] = readIntBigEndian(input, offset)
        }
    }

    private fun fillExtendedScheduleWords(schedule: IntArray) {
        for (wordIndex in INITIAL_SCHEDULE_WORDS until MESSAGE_SCHEDULE_WORDS) {
            val smallSigmaOne = rotateRight(schedule[wordIndex - 2], 17) xor
                rotateRight(schedule[wordIndex - 2], 19) xor
                (schedule[wordIndex - 2] ushr 10)
            val smallSigmaZero = rotateRight(schedule[wordIndex - 15], 7) xor
                rotateRight(schedule[wordIndex - 15], 18) xor
                (schedule[wordIndex - 15] ushr 3)

            schedule[wordIndex] = schedule[wordIndex - 16] +
                smallSigmaZero +
                schedule[wordIndex - 7] +
                smallSigmaOne
        }
    }

    private fun compressChunk(schedule: IntArray, hashValues: IntArray) {
        var firstValue = hashValues[0]
        var secondValue = hashValues[1]
        var thirdValue = hashValues[2]
        var fourthValue = hashValues[3]
        var fifthValue = hashValues[4]
        var sixthValue = hashValues[5]
        var seventhValue = hashValues[6]
        var eighthValue = hashValues[7]

        for (roundIndex in 0 until MESSAGE_SCHEDULE_WORDS) {
            val bigSigmaOne = rotateRight(fifthValue, 6) xor rotateRight(fifthValue, 11) xor rotateRight(fifthValue, 25)
            val choose = (fifthValue and sixthValue) xor (fifthValue.inv() and seventhValue)
            val firstTemporary = eighthValue + bigSigmaOne + choose + ROUND_CONSTANTS[roundIndex] + schedule[roundIndex]
            val bigSigmaZero = rotateRight(firstValue, 2) xor rotateRight(firstValue, 13) xor rotateRight(firstValue, 22)
            val majority = (firstValue and secondValue) xor (firstValue and thirdValue) xor (secondValue and thirdValue)
            val secondTemporary = bigSigmaZero + majority

            eighthValue = seventhValue
            seventhValue = sixthValue
            sixthValue = fifthValue
            fifthValue = fourthValue + firstTemporary
            fourthValue = thirdValue
            thirdValue = secondValue
            secondValue = firstValue
            firstValue = firstTemporary + secondTemporary
        }

        hashValues[0] += firstValue
        hashValues[1] += secondValue
        hashValues[2] += thirdValue
        hashValues[3] += fourthValue
        hashValues[4] += fifthValue
        hashValues[5] += sixthValue
        hashValues[6] += seventhValue
        hashValues[7] += eighthValue
    }

    private fun hashValuesToBytes(hashValues: IntArray): ByteArray {
        val output = ByteArray(size = HASH_BYTES)

        for (hashIndex in hashValues.indices) {
            writeIntBigEndian(output, hashIndex * INT_BYTES, hashValues[hashIndex])
        }

        return output
    }

    private fun rotateRight(value: Int, bitCount: Int): Int {
        return (value ushr bitCount) or (value shl (Int.SIZE_BITS - bitCount))
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and BYTE_MASK) shl 24) or
            ((bytes[offset + 1].toInt() and BYTE_MASK) shl 16) or
            ((bytes[offset + 2].toInt() and BYTE_MASK) shl 8) or
            (bytes[offset + 3].toInt() and BYTE_MASK)
    }

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeLongBigEndian(bytes: ByteArray, offset: Int, value: Long) {
        for (byteIndex in 0 until LENGTH_BYTES) {
            val shift = (LENGTH_BYTES - 1 - byteIndex) * BITS_PER_BYTE
            bytes[offset + byteIndex] = (value ushr shift).toByte()
        }
    }
}
