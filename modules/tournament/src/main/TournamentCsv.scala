package lila.tournament

import akka.stream.scaladsl.Source

object TournamentCsv:

  def apply(results: Source[Player.Result, ?]): Source[String, ?] = {
    Source(
      List(
        toCsv(
          "Rank",
          "Title",
          "Username",
          "Rating",
          "Score",
          "Performance",
          "Team",
          "Games Played",
          "Sheet"
        )
      )
    ) concat
      results.map(apply)
}

  def apply(p: Player.Result): String = p match
    case Player.Result(player, user, rank, sheet, games) =>
      toCsv(
        rank.toString,
        user.title.so(_.toString),
        user.name.value,
        player.rating.toString,
        player.score.toString,
        player.performanceOption.so(_.toString),
        ~player.team.map(_.value),
        ~games,
        sheet.so(_.scoresToString)
      )

  private def toCsv(values: String*) = values mkString ","
