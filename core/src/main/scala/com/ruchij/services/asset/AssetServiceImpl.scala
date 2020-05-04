package com.ruchij.services.asset

import cats.data.OptionT
import cats.{ApplicativeError, MonadError}
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.exceptions.ResourceNotFoundException
import com.ruchij.services.asset.models.Asset
import com.ruchij.services.asset.models.Asset.FileRange
import com.ruchij.services.repository.RepositoryService

class AssetServiceImpl[F[_]: MonadError[*[_], Throwable]](
  fileResourceDao: FileResourceDao[F],
  repositoryService: RepositoryService[F]
) extends AssetService[F] {

  override def retrieve(id: String, start: Option[Long], end: Option[Long]): F[Asset[F]] =
    OptionT(fileResourceDao.getById(id))
      .flatMap { fileResource =>
        val fileRange = start.map(first => FileRange(first, end.getOrElse(fileResource.size)))

        OptionT(repositoryService.read(fileResource.path, fileRange.map(_.start), fileRange.map(_.end)))
          .map { stream =>
            Asset[F](fileResource, stream, fileRange)
          }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
