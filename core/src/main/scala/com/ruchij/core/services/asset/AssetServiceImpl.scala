package com.ruchij.core.services.asset

import cats.data.OptionT
import cats.{ApplicativeError, MonadError, ~>}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.asset.models.Asset
import com.ruchij.core.services.asset.models.Asset.FileRange
import com.ruchij.core.services.repository.RepositoryService

class AssetServiceImpl[F[_]: MonadError[*[_], Throwable], T[_]](
  fileResourceDao: FileResourceDao[T],
  repositoryService: RepositoryService[F]
)(implicit transaction: T ~> F) extends AssetService[F] {

  override def retrieve(id: String, start: Option[Long], end: Option[Long]): F[Asset[F]] =
    OptionT(transaction(fileResourceDao.getById(id)))
      .flatMap { fileResource =>
        val fileRange = start.map(first => FileRange(first, end.getOrElse(fileResource.size)))

        OptionT(repositoryService.read(fileResource.path, fileRange.map(_.start), fileRange.map(_.end)))
          .map { stream =>
            models.Asset[F](fileResource, stream, fileRange)
          }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
