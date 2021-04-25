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
        OptionT(repositoryService.read(fileResource.path, start, end))
          .map { stream =>
            Asset[F](fileResource, stream, FileRange(start.getOrElse(0), end.getOrElse(fileResource.size)))
          }
      }
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException("Asset not found"))
      }

}
