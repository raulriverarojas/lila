package lila.system

import model._
import memo._
import scalaz.effects._

final class InternalApi(repo: GameRepo, versionMemo: VersionMemo) {

  def join(fullId: String, url: String, messages: String): IO[Unit] = for {
    gameAndPlayer ← repo player fullId
    (g1, player) = gameAndPlayer
    g2 = g1 withEvents decodeMessages(messages)
    g3 = g2.withEvents(g2.opponent(player).color, List(RedirectEvent(url)))
    _ ← repo.applyDiff(g1, g3)
    _ ← versionMemo put g3
  } yield ()

  def talk(gameId: String, author: String, message: String): IO[Unit] = for {
    g1 ← repo game gameId
    g2 = g1 withEvents List(MessageEvent(author, message))
    _ ← repo.applyDiff(g1, g2)
    _ ← versionMemo put g2
  } yield ()

  def end(gameId: String, messages: String): IO[Unit] = for {
    g1 ← repo game gameId
    g2 = g1 withEvents (EndEvent() :: decodeMessages(messages))
    _ ← repo.applyDiff(g1, g2)
    _ ← versionMemo put g2
  } yield ()

  def updateVersion(gameId: String): IO[Unit] =
    repo game gameId flatMap versionMemo.put

  private def decodeMessages(messages: String): List[MessageEvent] =
    (messages split '$').toList map { MessageEvent("system", _) }
}
