package me.timschneeberger.rootlessjamesdsp.api

import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigSite
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Retrofit-интерфейс для SquigLink legacy API.
 *
 * Каждый squig.link инстанс предоставляет:
 * - phone_book.json — каталог брендов и моделей (JSON)
 * - {fileName} (L).txt — файл АЧХ левого канала (TSV)
 * - {fileName} (R).txt — файл АЧХ правого канала (TSV)
 * - {targetName}.txt — файл целевой кривой (TSV)
 *
 * URL конструкция: {baseUrl}/{dir}{fileName}.txt
 * Пробелы в именах файлов URL-кодируются как %20.
 *
 * @param dir директория данных инстанса (например "data/")
 * @param fileName имя файла без расширения (например "64 Audio Aspire 1 (L)")
 */
interface SquigLinkService {

    /**
     * Загрузка каталога squigsites.json — список всех squig.link инстансов.
     * @return Call со списком инстансов
     */
    @GET("squigsites.json?squig")
    fun getSquigSites(): Call<List<SquigSite>>

    /**
     * Загрузка config.js — конфигурация сайта squig.link (список target curves).
     * @return Call с raw JS текстом
     */
    @GET("config.js")
    fun getConfigJs(): Call<String>

    /**
     * Загрузка каталога phone_book.json.
     * @param dir директория данных ("data/")
     * @return Call со списком брендов
     */
    @GET("{dir}phone_book.json")
    fun getPhoneBook(@Path(value = "dir", encoded = true) dir: String): Call<List<SquigLinkBrand>>

    /**
     * Загрузка файла АЧХ в формате TSV.
     * @param dir директория данных ("data/") — encoded=true чтобы не кодировать слеш
     * @param fileName имя файла без расширения (например "64 Audio Aspire 1 (L)")
     * @return Call с raw TSV текстом
     */
    @GET("{dir}{fileName}.txt")
    fun getFrequencyResponse(@Path(value = "dir", encoded = true) dir: String, @Path("fileName") fileName: String): Call<String>

    /**
     * Streaming-загрузка для больших файлов (например целевые кривые).
     * @param dir директория данных ("data/") — encoded=true чтобы не кодировать слеш
     * @param fileName имя файла без расширения
     * @return Call с raw текстом
     */
    @Streaming
    @GET("{dir}{fileName}.txt")
    fun downloadFile(@Path(value = "dir", encoded = true) dir: String, @Path("fileName") fileName: String): Call<String>
}
