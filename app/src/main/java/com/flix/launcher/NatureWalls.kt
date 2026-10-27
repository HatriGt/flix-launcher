package com.flix.launcher

/**
 * The default background: mountains, lakes, fjords and waterfalls from Wikimedia
 * Commons' featured pictures. Served at 1920 px wide and cached on disk after the
 * first showing, so the APK carries none of them.
 */
object NatureWalls {
    private fun place(path: String, name: String): AerialSource.Media {
        val file = path.substringAfterLast('/')
        return AerialSource.Media("https://upload.wikimedia.org/wikipedia/commons/thumb/$path/1920px-$file", false, name)
    }

    val all = listOf(
        place("6/6c/1_lake_louise_pano_2019.jpg", "Lake Louise, Canada"),
        place("4/44/Breathtaking_beauty_of_Dzukou_Valley_in_Manipur-Nagaland_border_%28edit%29.jpg", "Dzukou Valley, India"),
        place("7/77/Fiordo_de_Geiranger_desde_Flydalsjuvet%2C_Noruega%2C_2019-09-07%2C_DD_59.jpg", "Geirangerfjord, Norway"),
        place("b/b9/Himalayas%2C_Nepal.jpg", "Himalayas, Nepal"),
        place("0/07/Go%C3%B0afoss_July_2014.JPG", "Goðafoss, Iceland"),
        place("8/89/Gullfoss%2C_Su%C3%B0urland%2C_Islandia%2C_2014-08-16%2C_DD_123.JPG", "Gullfoss, Iceland"),
        place("7/74/Flatirons_Sunrise.jpg", "Flatirons, Colorado"),
        place("d/db/2015_Spullersee_im_Lechquellengebirge.jpg", "Spullersee, Austria"),
        place("6/67/Eilean_Donan_Castle%2C_Scotland_-_Jan_2011.jpg", "Loch Duich, Scotland"),
        place("8/82/Mount_Shasta%2C_Lake_Siskiyou%2C_SW_view.jpg", "Mount Shasta, California"),
        place("6/69/Glacier_de_Mont-de-Lans_-_France_-_2020.jpg", "Mont-de-Lans Glacier, France"),
        place("d/d2/View_to_Sifjorden_from_Sifjord%2C_Senja%2C_Troms%2C_Norway_in_2014_August.jpg", "Senja, Norway"),
        place("8/84/Lac_de_Montriond_06.jpg", "Lac de Montriond, France"),
        place("1/17/Montmorency_waterfall%2C_Qu%C3%A9bec%2C_Qu%C3%A9bec.jpg", "Montmorency Falls, Québec"),
    )
}
