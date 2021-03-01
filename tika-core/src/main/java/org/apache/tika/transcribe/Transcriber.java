/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tika.transcribe;

import org.apache.tika.exception.TikaException;

import java.io.IOException;


/**
 * Interface for Transcriber services.
 *
 * @since Tika TODO
 */
public interface Transcriber {
    /**
     * Transcribe the given audio file.
     *
     * @param filePath The path of the file to be transcribed.
     * @return key for transcription lookup
     * @throws TikaException When there is an error transcribing.
     * @throws IOException
     * @since TODO
     */

    public String startTranscribeAudio(String filePath) throws TikaException, IOException;

    /**
     * Transcribe the given the audio file and the source language.
     *
     * @param filePath       The path of the file to be transcribed.
     * @param sourceLanguage The language code for the language used in the input media file.
     * @return key for transcription lookup
     * @throws TikaException       When there is an error transcribing.
     * @throws java.io.IOException
     * @since TODO
     */
    public String startTranscribeAudio(String filePath, String sourceLanguage) throws TikaException, IOException;

    /**
     * Transcribe the given the video file.
     *
     * @param filePath The path of the file to be transcribed.
     * @return key for transcription lookup
     * @throws TikaException       When there is an error transcribing.
     * @throws java.io.IOException
     * @since TODO
     */
    public String startTranscribeVideo(String filePath) throws TikaException, IOException;

    /**
     * Transcribe the given the video file and the source language.
     *
     * @param filePath       The path of the file to be transcribed.
     * @param sourceLanguage The language code for the language used in the input media file.
     * @return key for transcription lookup
     * @throws TikaException       When there is an error transcribing.
     * @throws java.io.IOException
     * @since TODO
     */
    public String startTranscribeVideo(String filePath, String sourceLanguage) throws TikaException, IOException;

    /**
     * @return true if this Transcriber is probably able to translate right now.
     * @since Tika TODO
     */
    public boolean isAvailable();
}
