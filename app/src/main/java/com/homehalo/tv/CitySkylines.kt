package com.homehalo.tv

/**
 * The default background: city skylines at dusk and night, from Wikimedia Commons'
 * featured pictures. Served at 1920 px wide and cached on disk after the first showing,
 * so the APK carries none of them.
 */
object CitySkylines {
    private fun city(path: String, name: String): AerialSource.Media {
        val file = path.substringAfterLast('/')
        return AerialSource.Media("https://upload.wikimedia.org/wikipedia/commons/thumb/$path/1920px-$file", false, name)
    }

    val all = listOf(
        city("a/a4/Hong_Kong_Harbour_Night_2019-06-11.jpg", "Hong Kong"),
        city("2/2b/View_of_the_Ferry_Building_from_Pier_1%2C_San_Francisco_dllu.jpg", "San Francisco"),
        city("7/72/Ch%C3%A2teau_Frontenac_city_at_night.jpg", "Québec City"),
        city("9/9d/Frankfurt_Am_Main-Stadtansicht_von_der_Deutschherrnbruecke_zu_Beginn_der_Abenddaemmerung.jpg", "Frankfurt"),
        city("3/3c/Sunset_Toronto_Skyline_Panorama_Crop_from_Snake_Island.jpg", "Toronto"),
        city("2/2c/Prague_skyline_at_dawn.jpg", "Prague"),
        city("a/a1/Las_Vegas_%28Nevada%2C_USA%29%2C_The_Strip_--_2012_--_6232.jpg", "Las Vegas"),
        city("1/19/Cityscape_and_skyline_by_the_Copenhagen_Lakes%2C_Denmark_-_%2836018109956%29.jpg", "Copenhagen"),
        city("5/51/International_Commerce_Centre_on_Victoria_Harbour.jpg", "Victoria Harbour, Hong Kong"),
        city("d/de/St_Louis_night_expblend.jpg", "St. Louis"),
        city("6/6e/London_Thames_Sunset_panorama_-_Feb_2008.jpg", "London"),
        city("f/f4/Qu%C3%A9bec_city_at_night%2C_view_from_L%C3%A9vis_city.jpg", "Québec City by night"),
        city("b/b2/Skyline_Frankfurt_2011-01.jpg", "Frankfurt skyline"),
        city("5/5e/Vista_del_Puerto_de_Victoria_desde_Sky100%2C_Hong_Kong%2C_2013-08-09%2C_DD_10.JPG", "Hong Kong from Sky100"),
    )
}
